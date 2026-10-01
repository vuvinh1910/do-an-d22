# Quy trình phát triển (Development Workflow)

Tài liệu này hướng dẫn cách nhóm 3 người phối hợp làm việc hàng ngày, sử dụng Git và AI IDE.

---

## 1. Quy ước Git & Branching

* **`main`**: Branch ổn định nhất, chỉ merge khi tính năng đã pass test.
* **`develop`**: Branch tích hợp chung cho cả 3 thành viên.

### Phân nhánh tính năng theo thành viên:
* **Member 1 (OpenAPI & Test Gen)**:
  * `feature/m1-openapi-parser`
  * `feature/m1-test-generator-boundary`
  * `feature/m1-test-generator-negative`
  * `feature/m1-api-chaining`
* **Member 2 (Execution Worker)**:
  * `feature/m2-http-client-runner`
  * `feature/m2-assertion-engine`
  * `feature/m2-rabbitmq-consumer`
  * `feature/m2-concurrency-timeout`
* **Member 3 (Platform Backend & Web UI)**:
  * `feature/m3-project-env-management`
  * `feature/m3-test-run-orchestrator`
  * `feature/m3-dashboard-analytics-fe`
  * `feature/m3-report-detail-view`

---

## 2. Quy trình làm việc độc lập từ Ngày 1 (Parallel Coding)

```text
       ┌───────────┐         ┌───────────┐         ┌───────────┐
       │ Member 1  │         │ Member 2  │         │ Member 3  │
       └─────┬─────┘         └─────┬─────┘         └─────┬─────┘
             │                     │                     │
     Đọc file OpenAPI      Dùng file mock:       Dùng file mock:
     thật hoặc mẫu         testcase-contract     result-contract
             │                     │                     │
             ▼                     ▼                     ▼
     Parse & Sinh ra        Viết Worker bắn       Vẽ Dashboard &
     TestCase JSON         HTTP & Assertion      Báo cáo kết quả
```

1. **Member 1**: Tập trung viết `OpenApiParserService` đọc file Swagger/OpenAPI `petstore.json` hoặc sample api $\rightarrow$ chuyển đổi thành các object `ApiOperation` $\rightarrow$ sinh ra các `TestCase`.
2. **Member 2**: Lấy mẫu JSON từ `docs/api-contract/testcase-contract.md` $\rightarrow$ viết `HttpRunnerService` gửi request $\rightarrow$ viết `AssertionEngine` so khớp kết quả $\rightarrow$ trả ra `TestResult`.
3. **Member 3**: Thiết lập DB Migration Flyway, CRUD cho Project / Environment, viết giao diện React. Lấy mẫu JSON từ `docs/api-contract/result-contract.md` để render bảng kết quả và biểu đồ Pass/Fail.

---

## 3. Definition of Done (DoD) trước khi tạo Pull Request

Mỗi khi AI sinh xong code cho một task, bạn phải kiểm tra:
1. [ ] Code biên dịch thành công (`mvn clean compile` hoặc `npm run build`).
2. [ ] Có ít nhất 1-2 Unit Test kiểm thử logic (`mvn test`).
3. [ ] Không commit file thừa, file cấu hình máy cá nhân, `node_modules/` hay `target/`.
4. [ ] Tạo Pull Request vào `develop` và nhờ đồng đội review.
