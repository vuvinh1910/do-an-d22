# Contract: TestExecutionJob (RabbitMQ Payload)

Tài liệu này định nghĩa cấu trúc dữ liệu của một **Job chạy kiểm thử** được **Backend (Member 3)** đẩy vào RabbitMQ và **Worker (Member 2)** nhận để thực thi.

* **RabbitMQ Exchange**: `test.exchange` (Direct)
* **Routing Key**: `test.execution.key`
* **Queue Name**: `test.execution.queue`

---

## JSON Message Payload

```json
{
  "jobId": "f78a1092-23bd-4601-831d-b8d0092fa112",
  "testRunId": "b182cb22-c941-4556-9b88-518da06e0019",
  "projectId": "73c6838a-ff99-4c12-9c12-3200df189021",
  "environment": {
    "id": "e9324a33-3112-4211-9a77-440182aa1234",
    "name": "Staging",
    "baseUrl": "https://api-staging.example.com",
    "variables": {
      "auth_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
      "companyId": "comp_9981"
    }
  },
  "settings": {
    "timeoutMs": 10000,
    "maxRetries": 1,
    "concurrency": 5,
    "stopOnFirstFailure": false
  },
  "testCases": [
    {
      "id": "e4b17f8a-9821-4f7b-a2c9-d58611122334",
      "operationId": "createUser",
      "name": "Create user boundary test",
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
          "age": 18
        }
      },
      "assertions": [
        {
          "id": "rule-1",
          "type": "STATUS_CODE",
          "expected": 201
        }
      ],
      "extractions": [
        {
          "variableName": "createdUserId",
          "source": "BODY",
          "jsonPath": "$.id"
        }
      ]
    }
  ]
}
```

---

## Trách nhiệm của Worker khi nhận Job:
1. **Resolve Variables (Thế biến)**:
   * Tìm kiếm cú pháp `{{ten_bien}}` trong URL, Header, Query Params, Body và thay thế bằng giá trị từ `environment.variables` hoặc từ các biến được trích xuất từ các bước trước (`extractions`).
2. **Execute HTTP Request**:
   * Ghép `baseUrl` + `path`.
   * Gửi HTTP Request, đo thời gian phản hồi `responseTimeMs`.
3. **Run Assertions**:
   * Kiểm tra từng rule trong mảng `assertions`.
   * Nếu có assertion sai $\rightarrow$ Đánh dấu test case đó là `FAILED`.
4. **Extract Variables (Nếu có)**:
   * Trích xuất dữ liệu từ Response (Header/Body theo JSONPath) và cập nhật vào runtime context cho các step chạy sau trong cùng Job.
5. **Publish/Save Results**:
   * Lưu kết quả vào DB hoặc đẩy về queue `test.result.queue`.
