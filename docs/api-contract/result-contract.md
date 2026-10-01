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
