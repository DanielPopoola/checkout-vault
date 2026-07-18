package main

import (
	"bytes"
	"context"
	"net/http"
	"sync"
	"time"
)

// fireLoad sends requests to checkoutURL at approximately ratePerSecond
// for the given duration, returning every request's result. Each
// request runs in its own goroutine so a slow/hung request doesn't
// block the next tick from firing — this mirrors real concurrent client
// traffic, not a serial request-wait-request loop.
func fireLoad(ctx context.Context, client *http.Client, checkoutURL string, ratePerSecond int, duration time.Duration) []requestResult {
	interval := time.Second / time.Duration(ratePerSecond)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	deadline := time.Now().Add(duration)

	var wg sync.WaitGroup
	resultsCh := make(chan requestResult, ratePerSecond*int(duration.Seconds())+16)

	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			goto drain
		case <-ticker.C:
			wg.Add(1)
			go func() {
				defer wg.Done()
				resultsCh <- doCheckoutRequest(client, checkoutURL)
			}()
		}
	}

drain:
	wg.Wait()
	close(resultsCh)

	var results []requestResult
	for r := range resultsCh {
		results = append(results, r)
	}
	return results
}

func doCheckoutRequest(client *http.Client, checkoutURL string) requestResult {
	start := time.Now()

	body := bytes.NewBufferString(`{"order":"loadtest"}`)
	req, err := http.NewRequest(http.MethodPost, checkoutURL, body)
	if err != nil {
		return requestResult{latency: time.Since(start), success: false}
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	latency := time.Since(start)
	if err != nil {
		// Connection error, client-side timeout, etc. — never got a
		// response at all. This IS the isolation bug manifesting, if
		// it happens during an Inventory-only fault injection.
		return requestResult{latency: latency, success: false}
	}
	defer resp.Body.Close()

	return requestResult{latency: latency, success: resp.StatusCode == http.StatusOK}
}
