# OpenAPI Automated Testing Platform

Nền tảng tự động sinh và thực thi kịch bản kiểm thử REST API dựa trên đặc tả OpenAPI (Swagger/OpenAPI 3.x).

---

## 🏗️ Cấu trúc dự án

```text
openapi-testing-platform/
│
├── frontend/             # Giao diện React + TypeScript + Vite
├── backend/              # Spring Boot Modular Monolith (Auth, Project, OpenAPI, Gen, Run)
├── worker/               # Test Execution Worker (RabbitMQ consumer, HTTP runner, Assertion)
├── sample-api/           # API mẫu (Spring Boot) cài sẵn lỗi để test thực nghiệm
│
├── docs/                 # Tài liệu kỹ thuật, ERD & API Contracts
│   ├── architecture/     # Kiến trúc hệ thống
│   ├── database/         # ERD, DB Schema
│   ├── api-contract/     # Hợp đồng dữ liệu giữa 3 thành viên
│   └── development/      # Hướng dẫn quy trình code
│
├── docker-compose.yml    # Khởi chạy PostgreSQL, RabbitMQ, v.v.
├── README.md
├── AGENTS.md             # Hướng dẫn AI Agent (Cursor, Claude, Antigravity)
└── .gitignore
```

---

## 🚀 Khởi động nhanh môi trường phụ trợ (Database & Broker)

Yêu cầu máy đã cài **Docker** và **Docker Compose**.

```bash
# Khởi động PostgreSQL và RabbitMQ
docker compose up -d postgres rabbitmq

# Kiểm tra trạng thái
docker compose ps
```

* **PostgreSQL**: `localhost:5432` | DB: `openapi_testing_db` | User: `postgres` | Pass: `postgrespassword`
* **RabbitMQ Management**: [http://localhost:15672](http://localhost:15672) | User: `rabbitmq` | Pass: `rabbitmqpassword`

---

## 👥 Phân chia trách nhiệm & Kế hoạch làm việc

Chi tiết bảng giao việc cụ thể cho từng thành viên (kèm checklist từng task và giai đoạn triển khai) được lưu tại:
👉 **[BẢNG PHÂN CHIA NHIỆM VỤ 3 NGƯỜI (tasks-division.md)](docs/development/tasks-division.md)**

* **Người 1 - OpenAPI & Test Generator**: Parse OpenAPI spec, sinh test case (`VALID`, `BOUNDARY`, `NEGATIVE`, `AUTH`), API Chaining.
* **Người 2 - Execution Engine & Worker**: HTTP Runner, Assertion Engine, RabbitMQ Worker, Concurrency & Retry.
* **Người 3 - Platform Backend & UI**: Project/Environment/Variable management, Test Run & Suite, Dashboard & Báo cáo thống kê, Web UI.

