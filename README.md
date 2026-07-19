# checkout-vault

This project helps you build a checkout service that stays resilient even when its external dependencies struggle. It solves the common problem of cascading failures in microservices by isolating calls to services like fraud, payment, and inventory. This ensures critical payment and fraud checks remain stable if an unrelated service slows down or hangs.

## Installation

Getting `checkout-vault` up and running on your local machine is straightforward. Follow these steps:

1.  **Clone the Repository**
    Start by cloning the project to your local machine:
    ```bash
    git clone https://github.com/DanielPopoola/checkout-vault.git
    cd checkout-vault
    ```

2.  **Start the Mock Services**
    The project uses Go-based mock services for Fraud, Payment, and Inventory. These will listen on ports `8080`, `8081`, and `8082` respectively.
    ```bash
    make mocks-up
    ```
    To stop them:
    ```bash
    make mocks-down
    ```

3.  **Start the Checkout Service**
    The `checkout-service` is a Spring Boot application. Before starting, you can choose the isolation mode in `checkout-service/src/main/resources/application.yml`. For demonstrating bulkhead isolation, set `checkout-vault.isolation.mode` to `isolated`.
    ```yaml
    checkout-vault:
      isolation:
        mode: isolated # Change to 'naive' to see the isolation bug
    ```
    Then, run the service:
    ```bash
    make checkout-run
    ```
    The service will run on port `8090`.

4.  **Set Up Observability (Prometheus + Grafana)**
    For real-time metrics and visualization of the bulkhead behavior, start Prometheus and Grafana.
    ```bash
    make observability-up
    ```
    You can access them here:
    *   **Prometheus**: `http://localhost:9090` (check "Status -> Targets" to confirm `checkout-service` is `UP`)
    *   **Grafana**: `http://localhost:3000` (the "checkout-vault — Bulkhead Isolation" dashboard is auto-provisioned)

    To stop them:
    ```bash
    make observability-down
    ```

## Usage

Once all services are running, you can use the `loadtest` tool to simulate traffic and verify the isolation mechanisms.

1.  **Run the Load Test / Verifier**
    The `loadtest` utility will send requests to the `checkout-service`, inject a fault into the Inventory mock mid-run, and then compare performance statistics between the baseline and fault-run phases.
    
    To run with default settings (10 requests/second, 15 seconds duration for each phase, Inventory set to `hang` mode):
    ```bash
    make loadtest
    ```
    You can customize the load test parameters:
    ```bash
    make loadtest RATE=50 FAULT_MODE=slow FAULT_DELAY_MS=3000 DURATION=20s
    ```
    *   `RATE`: Requests per second.
    *   `FAULT_MODE`: Mode to inject into Inventory (`hang` or `slow`).
    *   `FAULT_DELAY_MS`: Delay in milliseconds if `FAULT_MODE` is `slow`.
    *   `DURATION`: Duration of each phase (baseline and fault-run).

2.  **Observe Metrics in Grafana**
    While the load test is running, keep an eye on the Grafana dashboard (`http://localhost:3000`). You'll be able to see:
    *   Per-dependency latency (p50 / p99)
    *   Call outcome rates per dependency (success, failure, timeout, bulkhead\_full)
    *   Live semaphore permit availability for each dependency (this is the bulkhead visualized!)

    During fault injection (e.g., Inventory in `hang` mode), you should see Inventory's permit gauge pin at 0 while Fraud's and Payment's gauges remain active, proving their capacity was never touched.

## Features

### Resilient Checkout Workflow

The core `checkout-service` orchestrates calls to various downstream dependencies: Fraud, Payment, and Inventory. Fraud and Payment calls are sequential and critical, meaning a failure in one immediately stops the checkout process. The Inventory check, however, is fired concurrently and does not block the main response, marking the order as "pending confirmation" for inventory status. This design ensures the user gets a fast response while critical path dependencies maintain their isolation.

```mermaid
sequenceDiagram
  actor User
  participant CheckoutService as "Checkout Service"
  participant FraudClient as "Fraud Service"
  participant PaymentClient as "Payment Service"
  participant InventoryClient as "Inventory Service"

  User->>CheckoutService: POST /checkout (orderPayload)
  activate CheckoutService
  CheckoutService->>InventoryClient: async checkStock()
  activate InventoryClient
  InventoryClient-->>CheckoutService: Returns stock status (or times out)
  deactivate InventoryClient

  CheckoutService->>FraudClient: score(orderPayload)
  activate FraudClient
  FraudClient-->>CheckoutService: Fraud Result (Success/Failure)
  deactivate FraudClient

  alt Fraud Success
    CheckoutService->>PaymentClient: charge(orderPayload)
    activate PaymentClient
    PaymentClient-->>CheckoutService: Payment Result (Success/Failure)
    deactivate PaymentClient

    alt Payment Success
      CheckoutService-->>User: APPROVED ("pending confirmation")
    else Payment Failure
      CheckoutService-->>User: REJECTED ("payment failed")
    end
  else Fraud Failure
    CheckoutService-->>User: REJECTED ("fraud check failed")
  end
  deactivate CheckoutService
```

### Bulkhead Isolation with Dedicated HTTP Clients

This project implements the Bulkhead pattern using a combination of per-dependency semaphores and dedicated `HttpClient` instances. In "isolated" mode, each dependency (Fraud, Payment, Inventory) gets its own `HttpClient` with a small, explicitly sized thread pool and a semaphore to cap in-flight requests. This physical separation prevents a backlog of requests or hung connections in one dependency from consuming resources needed by others.

```mermaid
flowchart LR
    subgraph Config["Config"]
        CVProperties["CheckoutVaultProperties (application.yml)"]
    end
    subgraph ServiceLayer["Service Layer"]
        CheckoutService
    end
    subgraph ClientLayer["Client Layer"]
        FC["FraudClient"]
        PC["PaymentClient"]
        IC["InventoryClient"]
    end
    subgraph HttpClients["HTTP Clients (Isolated Mode)"]
        HCF["HttpClient (Fraud)"]
        HCP["HttpClient (Payment)"]
        HCI["HttpClient (Inventory)"]
    end
    subgraph Decorators["Dependency Isolation (Decorators)"]
        IDF["IsolatedDependencyClient (Fraud)"]
        IDP["IsolatedDependencyClient (Payment)"]
        IDI["IsolatedDependencyClient (Inventory)"]
    end
    subgraph ExternalMocks["External Mocks"]
        MOCKF["Fraud Mock"]
        MOCKP["Payment Mock"]
        MOCKI["Inventory Mock"]
    end
    CVProperties -- "Isolation Mode: isolated" --> HttpClientConfig
    CVProperties -- "Permits, Timeout" --> IDF
    CVProperties -- "Permits, Timeout" --> IDP
    CVProperties -- "Permits, Timeout" --> IDI
    HttpClientConfig -- "Provides" --> HCF
    HttpClientConfig -- "Provides" --> HCP
    HttpClientConfig -- "Provides" --> HCI
    FC -- "Uses" --> IDF
    PC -- "Uses" --> IDP
    IC -- "Uses" --> IDI
    IDF -- "Delegates" --> HCF
    IDP -- "Delegates" --> HCP
    IDI -- "Delegates" --> HCI
    CheckoutService --> FC
    CheckoutService --> PC
    CheckoutService --> IC
    HCF --> MOCKF
    HCP --> MOCKP
    HCI --> MOCKI
    style HCF fill:#2e1065,stroke:#8b5cf6,stroke-width:2px,color:#fff
    style HCP fill:#2e1065,stroke:#8b5cf6,stroke-width:2px,color:#fff
    style HCI fill:#2e1065,stroke:#8b5cf6,stroke-width:2px,color:#fff
    style MOCKF fill:#451a03,stroke:#f59e0b,stroke-width:2px,color:#fff
    style MOCKP fill:#451a03,stroke:#f59e0b,stroke-width:2px,color:#fff
    style MOCKI fill:#451a03,stroke:#f59e0b,stroke-width:2px,color:#fff
    style CheckoutService fill:#1D3557,stroke:#457B9D,stroke-width:2px,color:#fff
    style FC fill:#1D3557,stroke:#457B9D,stroke-width:2px,color:#fff
    style PC fill:#1D3557,stroke:#457B9D,stroke-width:2px,color:#fff
    style IC fill:#1D3557,stroke:#457B9D,stroke-width:2px,color:#fff
    style IDF fill:#0f172a,stroke:#3b82f6,stroke-width:2px,color:#fff
    style IDP fill:#0f172a,stroke:#3b82f6,stroke-width:2px,color:#fff
    style IDI fill:#0f172a,stroke:#3b82f6,stroke-width:2px,color:#fff
```

### Real-time Observability with Prometheus & Grafana

The system is instrumented with Micrometer to expose detailed metrics about dependency calls, latency percentiles (p50, p99), and the real-time state of each dependency's semaphore (permits available). Prometheus scrapes these metrics, which are then visualized in an auto-provisioned Grafana dashboard, providing clear insight into bulkhead behavior and system health.

```mermaid
sequenceDiagram
  participant CheckoutService as "Checkout Service"
  participant Micrometer as "Micrometer Metrics"
  participant Prometheus as "Prometheus Server"
  participant Grafana as "Grafana Dashboard"
  actor Developer

  CheckoutService->>Micrometer: Record call latency, outcome, permits
  Micrometer->>Prometheus: Expose /actuator/prometheus endpoint
  Prometheus->>CheckoutService: Scrape metrics (every 2s)
  Developer->>Grafana: View Dashboard
  Grafana->>Prometheus: Query metrics for visualization
  Prometheus-->>Grafana: Return time-series data
  Grafana-->>Developer: Display real-time graphs
```

## System Architecture / Design

The `checkout-vault` project demonstrates microservice resilience through a client-server architecture interacting with mock dependencies and an observability stack. The Go-based load test client simulates user traffic and controls the behavior of the mock services. The core `checkout-service`, built with Spring Boot, handles checkout requests and communicates with Fraud, Payment, and Inventory mock services. Prometheus and Grafana provide real-time monitoring of the system's health and isolation effectiveness.

```mermaid
flowchart LR
    Client["Load Test Client (Go)"]
    subgraph Core Services
        CheckoutService["Checkout Service (Spring Boot)"]
    end
    subgraph Mock Dependencies
        FraudMock["Fraud Mock (Go)"]
        PaymentMock["Payment Mock (Go)"]
        InventoryMock["Inventory Mock (Go)"]
    end
    subgraph Observability
        Prometheus["Prometheus"]
        Grafana["Grafana"]
    end

    Client --> CheckoutService -- "HTTP POST /checkout" --> FraudMock
    CheckoutService -- "HTTP POST /checkout" --> PaymentMock
    CheckoutService -- "HTTP GET /inventory/SKU-123" --> InventoryMock

    Client -- "Control Mock Mode" --> FraudMock
    Client -- "Control Mock Mode" --> PaymentMock
    Client -- "Control Mock Mode" --> InventoryMock

    CheckoutService --> Prometheus -- "Scrapes Metrics" --> Grafana

    style Client fill:#1e1b4b,stroke:#6366f1,stroke-width:2px,color:#fff
    style CheckoutService fill:#2e1065,stroke:#8b5cf6,stroke-width:2px,color:#fff
    style FraudMock fill:#451a03,stroke:#f59e0b,stroke-width:2px,color:#fff
    style PaymentMock fill:#451a03,stroke:#f59e0b,stroke-width:2px,color:#fff
    style InventoryMock fill:#451a03,stroke:#f59e0b,stroke-width:2px,color:#fff
    style Prometheus fill:#0f172a,stroke:#3b82f6,stroke-width:2px,color:#fff
    style Grafana fill:#022c22,stroke:#10b981,stroke-width:2px,color:#fff
```

## Technologies Used

| Technology | Description |
|---|---|
| ![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white) | Powers the `checkout-service` with its robust framework. |
| ![Java 21](https://img.shields.io/badge/Java-007396?style=for-the-badge&logo=java&logoColor=white) | Used for the `checkout-service`, leveraging virtual threads for concurrency. |
| ![Go](https://img.shields.io/badge/Go-00ADD8?style=for-the-badge&logo=go&logoColor=white) | Implements the lightweight mock services and the load test harness. |
| ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white) | Collects metrics for real-time monitoring and analysis. |
| ![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white) | Visualizes metrics through powerful dashboards for observability. |
| ![Micrometer](https://img.shields.io/badge/Micrometer-2A7AE6?style=for-the-badge&logo=spring&logoColor=white) | Provides a vendor-neutral application observability facade for instrumenting services. |

## Further Reading

*   [`TRADEOFFS.md`](https://github.com/DanielPopoola/checkout-vault/blob/main/TRADEOFFS.md) — Dive deeper into the project's design decisions, including the isolation mechanism justification, pool sizing derivation, the bulkhead-vs-circuit-breaker discussion, and insights into what broke first under stress.

## Author Info

*   **LinkedIn**: <https://www.linkedin.com/in/daniel-popoola-942aa8216/>
*   **X (Twitter)**: <https://x.com/iamuchihadan>

## Badges

[![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-007396?style=for-the-badge&logo=java&logoColor=white)](https://www.java.com/)
[![Go](https://img.shields.io/badge/Go-00ADD8?style=for-the-badge&logo=go&logoColor=white)](https://golang.org/)
[![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)](https://prometheus.io/)
[![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white)](https://grafana.com/)
[![Micrometer](https://img.shields.io/badge/Micrometer-2A7AE6?style=for-the-badge&logo=spring&logoColor=white)](https://micrometer.io/)

[![Readme was generated by Dokugen](https://img.shields.io/badge/Readme%20was%20generated%20by-Dokugen-brightgreen)](https://dokugen.samueltuoyo.com)
