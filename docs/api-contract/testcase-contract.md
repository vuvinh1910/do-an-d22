# Contract: TestCase DTO (Member 1 Output)

Tài liệu này định nghĩa cấu trúc dữ liệu của một **TestCase** do **Member 1 (OpenAPI & Test Generator)** sinh ra hoặc xuất ra. 

**Member 2 (Execution Worker)** dùng cấu trúc này để xây dựng HTTP Request và kiểm tra Assertion.

---

## JSON Schema & Ví dụ chuẩn

```json
{
  "id": "e4b17f8a-9821-4f7b-a2c9-d58611122334",
  "operationId": "createUser",
  "name": "Verify create user with age exactly minimum boundary (age = 18)",
  "testType": "BOUNDARY",
  "orderIndex": 1,
  
  "request": {
    "method": "POST",
    "path": "/api/v1/users",
    "headers": {
      "Content-Type": "application/json",
      "Authorization": "Bearer {{auth_token}}"
    },
    "pathParams": {},
    "queryParams": {},
    "body": {
      "username": "tester01",
      "email": "tester01@example.com",
      "age": 18,
      "role": "MEMBER"
    }
  },

  "assertions": [
    {
      "id": "rule-1",
      "type": "STATUS_CODE",
      "expected": 201,
      "description": "Expected HTTP 201 Created"
    },
    {
      "id": "rule-2",
      "type": "RESPONSE_TIME",
      "expected": 1500,
      "operator": "LESS_THAN",
      "description": "Response time should be under 1500ms"
    },
    {
      "id": "rule-3",
      "type": "JSON_SCHEMA",
      "expectedSchema": {
        "type": "object",
        "required": ["id", "username", "email", "age"],
        "properties": {
          "id": { "type": "string", "format": "uuid" },
          "username": { "type": "string" },
          "email": { "type": "string" },
          "age": { "type": "integer" }
        }
      },
      "description": "Response body matches OpenAPI 201 schema"
    },
    {
      "id": "rule-4",
      "type": "JSON_PATH",
      "jsonPath": "$.age",
      "operator": "EQUALS",
      "expected": 18,
      "description": "Created user age must equal 18"
    }
  ],

  "extractions": [
    {
      "variableName": "createdUserId",
      "source": "BODY",
      "jsonPath": "$.id",
      "description": "Extract newly created user ID for subsequent GET/DELETE tests"
    }
  ]
}
```

---

## Giải thích các trường:

### 1. `testType` (Phân loại test case):
* `VALID`: Dữ liệu chuẩn, thỏa mãn mọi điều kiện (Happy Path).
* `BOUNDARY`: Dữ liệu biên (min, max, min-1, max+1, chuỗi rỗng, vượt max length).
* `NEGATIVE`: Dữ liệu sai logic/nghiệp vụ.
* `MISSING_REQUIRED`: Thiếu các trường đánh dấu `required: true`.
* `TYPE_ERROR`: Sai kiểu dữ liệu (vd: truyền string vào integer, boolean vào object).
* `AUTH`: Không truyền token, hoặc truyền token giả lập lỗi (401/403).

### 2. `assertions.type`:
* `STATUS_CODE`: So sánh mã HTTP status (`expected`: 200, 201, 400, v.v.).
* `JSON_SCHEMA`: Kiểm tra cấu trúc body trả về có hợp lệ với schema trong OpenAPI không.
* `JSON_PATH`: Lấy giá trị theo JSONPath (`$.status`, `$.data.id`) và so sánh (`EQUALS`, `NOT_NULL`, `CONTAINS`, `GREATER_THAN`).
* `RESPONSE_TIME`: Giới hạn thời gian phản hồi (ms).
* `HEADER`: Kiểm tra Header (ví dụ: `Content-Type` chứa `application/json`).
