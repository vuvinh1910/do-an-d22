# Kiến trúc hệ thống (Architecture)

## 1. Mô hình tổng thể

Hệ thống được thiết kế theo mô hình **Modular Monolith** cho Core Backend kết hợp với **Worker Service riêng biệt** kết nối qua RabbitMQ để xử lý việc chạy test bất đồng bộ.

```text
                    React + TypeScript (Vite)
                              │  (REST / WebSocket)
                              ▼
                  Spring Boot Core Backend
                  (Port: 8080)
                  ├── Auth Module
                  ├── Project Module
                  ├── OpenAPI Ingestion & Parser
                  ├── Test Case Generator Engine
                  ├── Environment & Variables
                  ├── Test Run & Orchestrator
                  └── Report & Analytics
                              │
             ┌────────────────┴────────────────┐
             ▼                                 ▼
        PostgreSQL                         RabbitMQ
        (Data & JSONB)             (Exchange: test.exchange)
                                               │
                                               ▼ (Queue: test.execution.queue)
                                     Test Execution Worker
                                     (Port: 8081 / Headless)
                                     ├── Job Consumer
                                     ├── HTTP Execution Runner
                                     ├── Assertion Engine
                                     └── Concurrency / Rate Limiter
                                               │
                                               ▼
                                        Target REST API
```

---

## 2. Luồng dữ liệu (Data Pipeline)

```mermaid
sequenceDiagram
    autonumber
    actor User as Tester / Developer
    participant FE as React Frontend
    participant BE as Spring Boot Backend
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    participant WK as Execution Worker
    participant Target as Target API

    User->>FE: 1. Upload OpenAPI (JSON/YAML)
    FE->>BE: POST /api/v1/projects/{id}/specs
    BE->>BE: Parse OpenAPI & extract Operations
    BE->>DB: Lưu ApiOperations & Schemas
    User->>FE: 2. Bấm "Tự động sinh Test Cases"
    FE->>BE: POST /api/v1/operations/{id}/generate-tests
    BE->>BE: Rule-based Test Generator (Valid, Boundary, Missing, Negative)
    BE->>DB: Lưu TestCases (Dạng draft / ready)
    
    User->>FE: 3. Kích hoạt Test Run (Chọn Env: Staging)
    FE->>BE: POST /api/v1/test-runs (suiteId, envId)
    BE->>DB: Tạo TestRun record (Status = QUEUED)
    BE->>MQ: Push TestExecutionJob
    MQ->>WK: Worker nhận Job
    WK->>Target: Gửi HTTP Request với Env/Headers resolved
    Target-->>WK: Trả HTTP Response (Status, Body, Latency)
    WK->>WK: Chạy Assertion Engine (Status, JSON Schema, Custom)
    WK->>DB: Ghi TestResult vào DB
    WK->>BE: Notify hoàn thành (hoặc BE lắng nghe Result Queue)
    BE->>FE: WebSocket / Polling cập nhật tiến độ Run
```

---

## 3. Các quyết định kỹ thuật quan trọng (ADR)
1. **Dùng UUID v4 làm Primary Key**: Tránh xung đột ID khi scale, bảo mật hơn Auto-Increment ID.
2. **Dùng JSONB trong PostgreSQL**:
   - `request_template` (headers, query, body)
   - `expected_rules` (assertions, schemas)
   - `actual_response` (headers, body)
   Giúp cấu trúc dữ liệu cực kỳ linh hoạt mà không cần tạo hàng chục bảng EAV.
3. **Tách Worker riêng**: Việc thực thi hàng trăm HTTP request có thể mất vài phút. Nếu để chạy trực tiếp trong thread của Web Backend sẽ làm nghẽn Tomcat/HTTP connection pool. Worker riêng giúp kiểm soát concurrency và timeout độc lập.
