# Worker Service (Test Execution Engine)

Module này là một service độc lập chịu trách nhiệm bởi **Member 2**:
- Lắng nghe Message Queue từ RabbitMQ (`test.execution.queue`).
- Thay thế các biến động (`{{token}}`, `{{userId}}`).
- Gửi HTTP Request đến Target REST API.
- Đo thời gian phản hồi (Latency).
- Kiểm tra tính đúng đắn với **Assertion Engine** (Status Code, JSON Schema, JSONPath).
- Trích xuất dữ liệu cho các bước tiếp theo (Dependency Chaining).
- Lưu kết quả hoặc trả về queue kết quả.

## Cấu trúc package dự kiến:
```text
worker/src/main/java/com/platform/worker/
├── consumer/        # RabbitMQ Listener (Nhận TestExecutionJob)
├── runner/          # HTTP Request Executor (WebClient / HttpClient)
├── assertion/       # Assertion Engine (Status, JSON Schema Validator, JSONPath)
├── context/         # Variable Interpolation & Runtime Context (Chaining)
└── model/           # DTOs khớp với docs/api-contract/
```
