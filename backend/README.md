# Backend Module (Spring Boot Core)

Module này đảm nhận logic chính của nền tảng:
- Quản lý User, Project, Environment, Variables (Member 3)
- Upload, Parse OpenAPI JSON/YAML thành ApiOperation (Member 1)
- Sinh kịch bản kiểm thử (Rule-based Test Generation) (Member 1)
- Lập lịch và đẩy Job chạy test vào RabbitMQ (Member 3)
- Báo cáo và thống kê kết quả (Member 3)

## Cấu trúc package dự kiến (Modular Monolith):
```text
backend/src/main/java/com/platform/
├── auth/            # Authentication & JWT
├── project/         # Quản lý dự án & thành viên
├── environment/     # Quản lý môi trường (Dev, Staging, Prod) & Biến
├── openapi/         # Ingestion, Swagger/OpenAPI Parser, Schema extractor
├── testgenerator/   # Rule-based Test Case Generation Engine
├── testrun/         # Quản lý Test Suite, Test Run lifecycle & RabbitMQ Producer
└── report/          # Analytics, Báo cáo & Thống kê
```
