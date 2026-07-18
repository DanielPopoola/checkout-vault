package main

import "fmt"

// success rate must stay within 2% points of baseline
// p99 latency must stay within 25% of baseline.
const (
	maxSuccessRateDropPP = 2.0
	maxP99IncreasePct    = 0.25
)

type verdict struct {
	passed          bool
	successRateDiff float64 // baseline - faultRun, in percentage points
	p99IncreasePct  float64
	details         []string
}

func verify(baseline, faultRun runStats) verdict {
	v := verdict{passed: true}

	successDrop := baseline.successRate() - faultRun.successRate()
	v.successRateDiff = successDrop
	if successDrop > maxSuccessRateDropPP {
		v.passed = false
		v.details = append(v.details, fmt.Sprintf(
			"success rate dropped %.2f pp (baseline %.2f%% -> fault-run %.2f%%), exceeds %.1f pp threshold",
			successDrop, baseline.successRate(), faultRun.successRate(), maxSuccessRateDropPP))
	}

	if baseline.p99 > 0 {
		increasePct := float64(faultRun.p99-baseline.p99) / float64(baseline.p99)
		v.p99IncreasePct = increasePct
		if increasePct > maxP99IncreasePct {
			v.passed = false
			v.details = append(v.details, fmt.Sprintf(
				"p99 latency increased %.1f%% (baseline %s -> fault-run %s), exceeds %.0f%% threshold",
				increasePct*100, baseline.p99, faultRun.p99, maxP99IncreasePct*100))
		}
	}

	if v.passed {
		v.details = append(v.details, "Fraud/Payment-path latency and success rate stayed within acceptable band of baseline")
	}

	return v
}
