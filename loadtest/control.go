package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
)

// setMockMode calls a mock's /control endpoint to change its mode
// (and optionally its slow-mode delay) mid-run. Used to inject Inventory
// faults while Fraud/Payment stay in "normal".
func setMockMode(client *http.Client, mockControlURL string, mode string, delayMs int) error {
	payload := map[string]any{"mode": mode}
	if delayMs > 0 {
		payload["delay_ms"] = delayMs
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	resp, err := client.Post(mockControlURL, "application/json", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("control call failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("control call returned %d", resp.StatusCode)
	}
	return nil
}
