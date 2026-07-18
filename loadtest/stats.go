package main

import (
	"sort"
	"time"
)

// requestResult captures the outcome of a single /checkout call.
type requestResult struct {
	latency time.Duration
	success bool // true only for HTTP 200 APPROVED — see session notes:
	// a 422 REJECTED is a correct business outcome, not a system
	// failure, but in this project's fault-injection scenarios
	// (Inventory faulted, Fraud/Payment healthy) every request SHOULD
	// come back 200 APPROVED — so strictly-200 is actually a sensitive,
	// valid measure of the isolation bug, not a conflation of concerns.
}

// runStats is the aggregated summary of a batch of requestResults —
// what the verifier compares between baseline and fault runs.
type runStats struct {
	total       int
	successes   int
	p50         time.Duration
	p99         time.Duration
}

func (r runStats) successRate() float64 {
	if r.total == 0 {
		return 0
	}
	return float64(r.successes) / float64(r.total) * 100
}

// summarize computes runStats from a slice of requestResults. Latency
// percentiles are computed only over successful requests — a failed
// request's "latency" (e.g. a connection error) isn't a meaningful
// latency sample, it's a different kind of data point entirely.
func summarize(results []requestResult) runStats {
	stats := runStats{total: len(results)}

	var latencies []time.Duration
	for _, r := range results {
		if r.success {
			stats.successes++
			latencies = append(latencies, r.latency)
		}
	}

	if len(latencies) == 0 {
		return stats
	}

	sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	stats.p50 = percentile(latencies, 0.50)
	stats.p99 = percentile(latencies, 0.99)
	return stats
}

func percentile(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(float64(len(sorted)) * p)
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}
