# checkout-vault — local dev convenience targets.
# PID files under .pids/ track background mock processes so `make mocks-down`
# can cleanly stop them without hunting `ps` output by hand.

PIDS_DIR := .pids

.PHONY: mocks-up mocks-down checkout-run clean-pids loadtest

# Configurable via: make loadtest RATE=50 DURATION=15s FAULT_MODE=slow FAULT_DELAY_MS=3000
RATE ?= 10
DURATION ?= 15s
FAULT_MODE ?= hang
FAULT_DELAY_MS ?= 3000

mocks-up:
	@mkdir -p $(PIDS_DIR)
	@echo "Starting Fraud on :8080..."
	@( cd mocks && nohup go run main.go -port=8080 -name=fraud -path=/fraud/score \
		> ../$(PIDS_DIR)/fraud.log 2>&1 & echo $$! > $(PIDS_DIR)/fraud.pid )
	@echo "Starting Payment on :8081..."
	@( cd mocks && nohup go run main.go -port=8081 -name=payment -path=/payment/charge \
		> ../$(PIDS_DIR)/payment.log 2>&1 & echo $$! > $(PIDS_DIR)/payment.pid )
	@echo "Starting Inventory on :8082..."
	@( cd mocks && nohup go run main.go -port=8082 -name=inventory -path=/inventory/SKU-123 \
		> ../$(PIDS_DIR)/inventory.log 2>&1 & echo $$! > $(PIDS_DIR)/inventory.pid )
	@sleep 1
	@echo "Mocks started. Logs in $(PIDS_DIR)/*.log — run 'make mocks-down' to stop."

mocks-down:
	@for name in fraud payment inventory; do \
		if [ -f $(PIDS_DIR)/$$name.pid ]; then \
			kill $$(cat $(PIDS_DIR)/$$name.pid) 2>/dev/null && echo "Stopped $$name" || echo "$$name already stopped"; \
			rm -f $(PIDS_DIR)/$$name.pid; \
		fi \
	done

clean-pids:
	@rm -rf $(PIDS_DIR)

checkout-run:
	@cd checkout-service && mvn spring-boot:run

loadtest:
	@cd loadtest && go run . \
		-rate=$(RATE) \
		-duration=$(DURATION) \
		-fault-mode=$(FAULT_MODE) \
		-fault-delay-ms=$(FAULT_DELAY_MS)
