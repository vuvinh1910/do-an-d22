# Contract: TestResult DTO (Member 2 Output)

Tài liệu này định nghĩa cấu trúc dữ liệu của **Kết quả thực thi (TestResult)** do **Member 2 (Execution Worker)** trả về sau khi chạy một Test Case. 

**Member 3 (Platform / Report / UI)** dùng dữ liệu này để lưu vào DB, cập nhật tiến độ Test Run và hiển thị lên Dashboard.

---

## JSON Payload của một Test Result

```json
{
  "testRunId": "b182cb22-c941-4556-9b88-518da06e0019",
  "testCaseId": "e4b17f8a-9821-4f7b-a2c9-d58611122334",
  "name": "Verify create user with age exactly minimum boundary (age = 18)",
  "testType": "BOUNDARY",
  
  "status": "PASSED",
  "httpStatus": 201,
  "responseTimeMs": 142,
  
  "requestSent": {
    "url": "https://api-staging.example.com/api/v1/users",
    "method": "POST",
    "headers": {
      "Content-Type": "application/json",
      "Authorization": "Bearer eyJhbGciOiJIUzI1NiIsInR5..."
    },
    "body": {
      "username": "tester01",
      "email": "tester01@example.com",
      "age": 18
    }
  },

  "responseReceived": {
    "statusCode": 201,
    "headers": {
      "content-type": "application/json;charset=UTF-8",
      "content-length": "108"
    },
    "body": {
      "id": "c71a3962-42da-4fba-bb90-e555191f4211",
      "username": "tester01",
      "email": "tester01@example.com",
      "age": 18,
      "createdAt": "2026-10-01T13:45:00Z"
    }
  },

  "assertionResults": [
    {
      "ruleId": "rule-1",
      "type": "STATUS_CODE",
      "passed": true,
      "expected": 201,
      "actual": 201,
      "message": "Status code matched 201"
    },
    {
      "ruleId": "rule-2",
      "type": "RESPONSE_TIME",
      "passed": true,
      "expected": 1500,
      "actual": 142,
      "message": "Response time 142ms is within limit 1500ms"
    },
    {
      "ruleId": "rule-3",
      "type": "JSON_SCHEMA",
      "passed": true,
      "expected": "object with required fields [id, username, email, age]",
      "actual": "valid",
      "message": "Schema validation passed"
    },
    {
      "ruleId": "rule-4",
      "type": "JSON_PATH",
      "passed": true,
      "expected": 18,
      "actual": 18,
      "message": "JSONPath '$.age' equals 18"
    }
  ],

  "errorMessage": null,
  "executedAt": "2026-10-01T20:59:00.123Z"
}
```

---

## Ví dụ khi Test bị FAILED (Lỗi 400 nhưng trả về 500 hoặc sai Schema):

```json
{
  "testRunId": "b182cb22-c941-4556-9b88-518da06e0019",
  "testCaseId": "f9911aa2-1111-4444-8888-1234567890ab",
  "name": "Verify create user with age < 18 (age = 17)",
  "testType": "BOUNDARY",
  
  "status": "FAILED",
  "httpStatus": 500,
  "responseTimeMs": 350,
  
  "assertionResults": [
    {
      "ruleId": "rule-status",
      "type": "STATUS_CODE",
      "passed": false,
      "expected": 400,
      "actual": 500,
      "message": "Expected HTTP Status 400 Bad Request but received 500 Internal Server Error"
    }
  ],
  "errorMessage": "Assertion failed: HTTP status mismatch"
}
```

---

## Quy ước trạng thái `status`:
* `PASSED`: Mọi assertion đều thành công.
* `FAILED`: Ít nhất một assertion không thỏa mãn (hoặc HTTP status không đúng kỳ vọng).
* `ERROR`: Lỗi mạng, timeout, không kết nối được đến server mục tiêu.
* `SKIPPED`: Bị bỏ qua (ví dụ do một bước phụ thuộc trước đó bị FAIL).

## `assertionResults` cho HEADER

Giữ nguyên sáu field kết quả rule; tên header nằm trong rule gốc (`headerName`), không thêm field vào result.

```json
{
  "ruleId": "rule-header",
  "type": "HEADER",
  "passed": true,
  "expected": "application/json",
  "actual": ["application/json;charset=UTF-8"],
  "message": "Header assertion passed"
}
```

- `actual` luôn là JSON array string chứa các giá trị của header được chọn, không split/join. Thiếu header hoặc list rỗng -> `[]`, passed false.
- Các entry khác casing được thu thập cùng nhau; thứ tự giữa các entry map không được đảm bảo, không phải tiêu chí PASS/FAIL. Thứ tự trong từng list được giữ.
- Với NOT_NULL, expected absent hoặc JSON null ở rule được trả thành `expected: null` trong result.
- Message không echo header name/value; `expected`/`actual` vẫn là dữ liệu raw, cần chính sách redaction ở tầng publish/persistence sau này.

## `assertionResults` cho JSON_SCHEMA (worker C5)

Giữ sáu field của ví dụ trên. `expected` là chuỗi cố định `Response body conforms to supplied Draft 7 schema` (schema gốc nằm trong rule), `actual` là `valid` khi PASS hoặc `invalid` khi FAIL.

- PASS: message `Schema validation passed`.
- JSON hợp lệ nhưng vi phạm schema: message `Schema validation failed`.
- Body không phải một JSON document hợp lệ: message `Response body is not valid JSON`, actual `invalid`.
- Không echo body, schema, path hoặc diagnostic thô của validator trong result/exception. Đây là kết quả tóm tắt, chưa có chi tiết keyword/path phục vụ UI.
- Schema cấu hình sai/chưa hỗ trợ -> IllegalArgumentException; execution error hoặc validator không thể hoàn tất -> IllegalStateException ở evaluator. Engine/orchestration chịu trách nhiệm mapping ERROR; không biến chúng thành assertion FAIL.

## Tổng hợp assertion (worker C6)

- Giữ thứ tự input và trả một result cho mỗi rule hợp lệ; tiếp tục sau assertion FAIL, không short-circuit. Tất cả PASS -> passed true; ít nhất một FAIL -> passed false.
- List rỗng explicit -> results rỗng, passed true (không có assertion failure); không tự suy ra HTTP 2xx. List null, rule null, id trống/trùng hoặc type chưa hỗ trợ là lỗi cấu hình, không bỏ qua rule.
- Lỗi cấu hình hoặc validator không hoàn tất làm lần evaluate không trả summary/partial results; execution error bị từ chối ngay cả khi list rỗng. Tầng orchestration sau này mapping ERROR, không publish PASSED/FAILED từ một lần evaluate chưa hoàn tất.
- Summary là object nội bộ, chưa phải TestResult DTO hay Spring/RabbitMQ integration. Không thêm trạng thái wire mới.
