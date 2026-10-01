# Sample REST API (Mục tiêu kiểm thử thực nghiệm)

Module này là một REST API độc lập được xây dựng để **kiểm chứng thực nghiệm năng lực của nền tảng**.

## Ý nghĩa trong Đồ án tốt nghiệp:
Hội đồng bảo vệ đồ án luôn hỏi:
> *"Làm sao các em chứng minh được nền tảng của mình phát hiện được lỗi thật của một API?"*

$\rightarrow$ `sample-api` chính là câu trả lời. Nhóm sẽ cung cấp một API mẫu có đặc tả OpenAPI chuẩn, nhưng **cố tình cài sẵn các lỗi vi phạm đặc tả (Contract Drift & Validation Bugs)**.

## Danh sách API & Các lỗi cố tình cài cắm:
1. `POST /api/v1/auth/login`:
   - Trả về JWT Token.
2. `POST /api/v1/users`:
   - Spec quy định `age >= 18` $\rightarrow$ **Cố tình không validate**: Cho phép `age = 15` tạo thành công (Bug 1).
   - Spec quy định `email` là bắt buộc (`required: true`) $\rightarrow$ **Cố tình không validate**: Bỏ trống email vẫn nhận 201 Created (Bug 2).
3. `GET /api/v1/users/{id}`:
   - Spec quy định response trả về các field `id, username, email, age` $\rightarrow$ **Cố tình trả sai schema**: Đổi field `email` thành `user_email` (Bug 3: Response Schema Mismatch).
4. `GET /api/v1/products`:
   - Phân trang `page`, `limit`.
5. `POST /api/v1/orders`:
   - Phụ thuộc: Cần `userId` từ API user và `token` từ Auth.

Khi nền tảng sinh test và chạy quét qua `sample-api`, báo cáo phải chỉ ra chính xác các điểm vi phạm trên.
