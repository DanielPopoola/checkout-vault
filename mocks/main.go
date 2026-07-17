package main

import (
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"sync"
	"time"
)

// mode represents the current simulated behaviour of this mock
type mode string

const (
	modeNormal mode = "normal"
	modeSlow   mode = "slow"
	modeHang   mode = "hang"
	modeError  mode = "error"
)

// state holds the mutable, concurrently-accessed configuration for this
// mock instance: which mode it's in, and how long to delay in slow mode.
type state struct {
	mu       sync.RWMutex
	mode     mode
	delay    time.Duration
	baseline time.Duration // fixed latency applied in "normal" mode
}

func newState(baseline time.Duration) *state {
	return &state{
		mode:     modeNormal,
		delay:    2 * time.Second, // default slow-mode delay until /control says otherwise
		baseline: baseline,
	}
}

// snapshot returns the current mode and delay under a read lock, then
// releases the lock immediately.
func (s *state) snapshot() (mode, time.Duration, time.Duration) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.mode, s.delay, s.baseline
}

func (s *state) set(m mode, delay time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.mode = m
	if delay > 0 {
		s.delay = delay
	}
}

type controlRequest struct {
	Mode    string `json:"mode"`
	DelayMs int    `json:"delay_ms,omitempty"`
}

func main() {
	port := flag.String("port", "8080", "port to listen on")
	name := flag.String("name", "fraud", "dependency name: fraud | payment | inventory")
	path := flag.String("path", "/fraud/score", "business endpoint path")
	baselineMs := flag.Int("baseline-ms", 50, "fixed latency (ms) applied in normal mode")
	flag.Parse()

	body, err := responseBodyFor(*name)
	if err != nil {
		log.Fatalf("startup: %v", err)
	}

	st := newState(time.Duration(*baselineMs) * time.Millisecond)

	mux := http.NewServeMux()
	mux.HandleFunc("/control", controlHandler(st))
	mux.HandleFunc(*path, businessHandler(st, body))
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})

	addr := ":" + *port
	log.Printf("[%s] listening on %s, business endpoint %s", *name, addr, *path)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("[%s] server failed: %v", *name, err)
	}
}

// responseBodyFor returns the hardcoded success payload for a given
// dependency name.
func responseBodyFor(name string) ([]byte, error) {
	switch name {
	case "fraud":
		return []byte(`{"risk":"low"}`), nil
	case "payment":
		return []byte(`{"charged":true}`), nil
	case "inventory":
		return []byte(`{"in_stock":true}`), nil
	default:
		return nil, &unknownNameError{name}
	}
}

type unknownNameError struct{ name string }

func (e *unknownNameError) Error() string {
	return "unknown -name " + e.name + ": must be fraud, payment, or inventory"
}

// controlHandler lets the caller change this mock's mode (and optionally
// its slow-mode delay) at runtime.
func controlHandler(st *state) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		var req controlRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid control payload", http.StatusBadRequest)
			return
		}

		m := mode(req.Mode)
		switch m {
		case modeNormal, modeSlow, modeHang, modeError:
			// valid
		default:
			http.Error(w, "mode must be one of: normal, slow, hang, error", http.StatusBadRequest)
			return
		}

		var delay time.Duration
		if req.DelayMs > 0 {
			delay = time.Duration(req.DelayMs) * time.Millisecond
		}
		st.set(m, delay)

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}
}

// businessHandler serves the "real" dependency call. Its behavior branches
// on the current mode, read via a single snapshot() call at the start —
// the mode cannot change mid-request (a request already in flight isn't
// affected by a /control call that arrives after it started).
func businessHandler(st *state, body []byte) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		m, delay, baseline := st.snapshot()

		switch m {
		case modeNormal:
			time.Sleep(baseline)
			writeJSON(w, http.StatusOK, body)

		case modeSlow:
			time.Sleep(delay)
			writeJSON(w, http.StatusOK, body)

		case modeHang:
			// Never respond. Block until the client gives up (timeout)
			// or disconnects. We select on the request context so the
			// goroutine doesn't leak once the client disconnects.
			<-r.Context().Done()

		case modeError:
			writeJSON(w, http.StatusInternalServerError, []byte(`{"error":"simulated failure"}`))

		default:
			// Should be unreachable — set() only accepts valid modes.
			writeJSON(w, http.StatusInternalServerError, []byte(`{"error":"unknown mode"}`))
		}
	}
}

func writeJSON(w http.ResponseWriter, status int, body []byte) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}
