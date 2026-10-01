# Frontend Module (React + TypeScript + Vite)

Giao diện Web của nền tảng kiểm thử API do **Member 3 lead** (cả 3 cùng đóng góp các màn hình thuộc module của mình).

## Các màn hình chính:
1. **Projects & Environments Dashboard**: Quản lý dự án, cấu hình môi trường (Dev/Staging/Prod), biến môi trường và secret token.
2. **OpenAPI Spec & API Operations Explorer**: Upload file OpenAPI (kéo thả hoặc dán link), xem danh sách endpoints, phương thức, request/response schema.
3. **Test Generator & Suite Builder**: Chọn API operation $\rightarrow$ Nhấn sinh test case $\rightarrow$ Xem và chỉnh sửa các test case được sinh ra (`VALID`, `BOUNDARY`, `NEGATIVE`, `MISSING_REQUIRED`).
4. **Test Run & Live Execution**: Kích hoạt chạy một Test Suite, theo dõi thanh tiến độ và kết quả realtime.
5. **Execution Report & Analytics**: Báo cáo chi tiết từng lần chạy:
   - Tổng quan: Pass rate, tổng số test, P95 latency.
   - Chi tiết: Xem request đã gửi, response nhận được, diff schema, assertion rule nào bị fail và tại sao.

## Cấu trúc thư mục dự kiến:
```text
frontend/src/
├── assets/          # Static assets (images, icons)
├── components/      # UI components tái sử dụng (Button, Modal, Table, Badge, CodeBlock)
├── features/        # Chia theo domain chức năng:
│   ├── auth/
│   ├── projects/
│   ├── openapi/
│   ├── testcases/
│   └── testruns/
├── services/        # API Client (Axios / Fetch)
├── types/           # TypeScript interfaces tương ứng với backend DTOs
└── pages/           # Các trang chính
```
