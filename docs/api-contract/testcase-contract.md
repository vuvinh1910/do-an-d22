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

### 3. Rule `HEADER` (bổ sung tương thích các rule cũ)

```json
{
  "id": "rule-header",
  "type": "HEADER",
  "headerName": "Content-Type",
  "operator": "CONTAINS",
  "expected": "application/json",
  "description": "Response declares a JSON content type"
}
```

- `headerName`: bắt buộc riêng cho HEADER, HTTP field-name ASCII token không rỗng (không khoảng trắng, dấu `:` hoặc CR/LF). Không tự trim. Các type khác không dùng field này; rule cũ không có field này không thay đổi.
- `operator`: bắt buộc, hỗ trợ `EQUALS`, `CONTAINS`, `NOT_NULL`. Không dùng `jsonPath`/`expectedSchema` trong HEADER.
- `EQUALS`/`CONTAINS`: `expected` bắt buộc là JSON string, không ép kiểu. So sánh bằng hoặc chứa substring literal, case-sensitive; chuỗi rỗng hợp lệ (substring rỗng match mọi giá trị có thật).
- Tên header không phân biệt hoa/thường. Nếu có nhiều giá trị, **ít nhất một** giá trị thỏa mãn là PASS. Thu thập cả các entry cùng tên nhưng khác casing; không split theo dấu phẩy, không join các giá trị, không trim/normalize giá trị hoặc áp dụng quy tắc riêng cho media type.
- `NOT_NULL`: expected absent hoặc explicit JSON null; PASS khi có ít nhất một giá trị, kể cả chuỗi rỗng. Header thiếu hoặc list giá trị rỗng là FAIL.
- Response HTTP 4xx/5xx hoàn chỉnh vẫn được đánh giá. Execution error không được biến thành kết quả assertion PASS/FAIL.
- Producer (Member 1) và UI/backend (Member 3) khi tạo/lưu HEADER rule dùng field `headerName` trên; đây là bổ sung payload, không đổi các rule hiện có. Triển khai các module đó không được suy ra chỉ từ thay đổi contract này.

### 4. Rule `JSON_SCHEMA` — worker C5

- `expectedSchema` bắt buộc là object hoặc boolean JSON Schema **Draft 7**; absent `$schema` mặc định Draft 7. Nếu khai báo, chỉ nhận `http://json-schema.org/draft-07/schema#` ở root. Dialect khác hoặc `$schema` lồng nhau là cấu hình chưa hỗ trợ.
- Không dùng `expected`, `operator`, `jsonPath`, `headerName`. Schema được kiểm tra bằng meta-schema Draft 7 đóng gói sẵn; schema sai là lỗi cấu hình, không phải assertion FAIL.
- Chấp nhận các keyword Draft 7; keyword lạ bị từ chối để tránh bỏ qua constraint sai chính tả hoặc sai dialect. Annotation như default/examples không được áp dụng để sửa response; contentEncoding/contentMediaType là annotation. Format assertions bật cho các format thư viện Networknt 1.5.1 nhận biết; format tùy biến chưa đăng ký chỉ là annotation.
- `$ref` chỉ hỗ trợ JSON Pointer nội bộ dạng `#/...`, có escaping `~0`/`~1`; chưa hỗ trợ URI percent-encoding, anchor, root `#`, resource `$id`, reference ngoài hoặc chu kỳ. Độ sâu schema/reference tối đa 64. Loader từ chối tải mọi resource ngoài, kể cả HTTP/file/classpath. Producer phải bundle/resolve schema trước khi gửi; các validation sibling của `$ref` bị từ chối vì Draft 7 sẽ bỏ qua chúng.
- Producer phải chuyển schema OpenAPI 3.0/3.1 về dialect này: ví dụ `nullable: true` đổi thành type union có `null`, chuyển exclusiveMinimum/Maximum boolean sang ngưỡng numeric. Không truyền nguyên OpenAPI Schema Object; chưa có tích hợp generator được kiểm chứng.
- Validate toàn bộ response JSON, không phụ thuộc HTTP 2xx hoặc Content-Type. JSON malformed/empty/duplicate-key/trailing-token -> FAIL, kể cả schema true. JSON null là giá trị hợp lệ để validate. Không ép kiểu string sang number; số so sánh theo giá trị, kể cả const/enum lồng nhau và uniqueItems.
- HTTP execution error hoặc validator không hoàn thành -> từ chối đánh giá; không tạo kết quả PASS/FAIL giả. C5 chưa có sandbox/time budget riêng cho schema/regex không tin cậy.
- Giới hạn cấu trúc trước meta-validation/chuẩn hóa trong evaluator: schema tối đa 10.000 JSON nodes, độ sâu JSON 256 (kể cả annotation data); giới hạn schema/reference 64 vẫn áp dụng. Số integral sau mở rộng exponent tối đa 10.000 chữ số để tránh cấp phát BigInteger không giới hạn. Schema vượt giới hạn -> lỗi cấu hình; response vượt miền số chuẩn hóa -> evaluation error, không giả lập FAIL. Giới hạn này không thay cho validation kích thước payload ở tầng nhận job/model.
