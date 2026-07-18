// Command loadtest is the checkout-vault load harness and verifier.
//
// It runs two phases against a running checkout-service instance:
//  1. Baseline — all mocks in "normal" mode.
//  2. Fault run — Inventory flipped to "slow" or "hang" mid-run; Fraud
//     and Payment stay "normal".
//
// It then compares Fraud/Payment-path stats (checkout endpoint success
// rate + p99 latency — see stats.go for why we don't attempt
// per-dependency attribution from outside) between the two runs against
// the thresholds locked in PLAN.md, and reports pass/fail.
package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"
)

func main() {
	checkoutURL := flag.String("checkout-url", "http://localhost:8090/checkout", "checkout service endpoint")
	inventoryControlURL := flag.String("inventory-control-url", "http://localhost:8082/control", "inventory mock's control endpoint")
	ratePerSecond := flag.Int("rate", 10, "requests per second")
	duration := flag.Duration("duration", 15*time.Second, "duration of each phase (baseline and fault-run)")
	faultMode := flag.String("fault-mode", "hang", "mode to inject into Inventory: slow | hang")
	faultDelayMs := flag.Int("fault-delay-ms", 3000, "delay (ms) to use if fault-mode is 'slow'")
	clientTimeout := flag.Duration("client-timeout", 5*time.Second, "load-test client's own HTTP timeout — generous, well above the checkout service's internal 500ms per-dependency timeout, so any failure we see is attributable to the server, not the harness giving up early")
	flag.Parse()

	httpClient := &http.Client{Timeout: *clientTimeout}
	ctx := context.Background()

	fmt.Println("=== checkout-vault load harness ===")
	fmt.Printf("target: %s | rate: %d req/s | phase duration: %s\n\n", *checkoutURL, *ratePerSecond, *duration)

	// Ensure Inventory starts from a clean "normal" state before baseline,
	// regardless of what a previous run left it in.
	if err := setMockMode(httpClient, *inventoryControlURL, "normal", 0); err != nil {
		fmt.Fprintf(os.Stderr, "failed to reset inventory mock to normal before baseline: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("--- Phase 1: baseline (all mocks normal) ---")
	baselineResults := fireLoad(ctx, httpClient, *checkoutURL, *ratePerSecond, *duration)
	baseline := summarize(baselineResults)
	printStats("baseline", baseline)

	fmt.Printf("\n--- Injecting fault: Inventory -> %s ---\n", *faultMode)
	if err := setMockMode(httpClient, *inventoryControlURL, *faultMode, *faultDelayMs); err != nil {
		fmt.Fprintf(os.Stderr, "failed to inject fault into inventory mock: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("--- Phase 2: fault-run (Inventory faulted, Fraud/Payment normal) ---")
	faultResults := fireLoad(ctx, httpClient, *checkoutURL, *ratePerSecond, *duration)
	faultRun := summarize(faultResults)
	printStats("fault-run", faultRun)

	// Reset Inventory back to normal so a subsequent run (or manual
	// testing) doesn't inherit a faulted mock by accident.
	_ = setMockMode(httpClient, *inventoryControlURL, "normal", 0)

	fmt.Println("\n--- Verdict ---")
	v := verify(baseline, faultRun)
	for _, d := range v.details {
		fmt.Println(" -", d)
	}

	if v.passed {
		fmt.Println("\nPASS: isolation held under Inventory fault injection.")
		os.Exit(0)
	}
	fmt.Println("\nFAIL: Fraud/Payment-path stats degraded beyond acceptable band.")
	os.Exit(1)
}

func printStats(label string, s runStats) {
	fmt.Printf("[%s] total=%d successes=%d success_rate=%.2f%% p50=%s p99=%s\n",
		label, s.total, s.successes, s.successRate(), s.p50, s.p99)
}
