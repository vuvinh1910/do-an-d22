# Kế hoạch rà soát chất lượng — Worker của Vinh (Người 2)

## 1. Hướng dẫn tiếp tục ở mỗi phiên

1. Đọc AGENTS.md tại gốc, tài liệu này và IMPLEMENTATION_PLAN.md trong worker.
2. Kiểm tra nhánh `vinh`, working tree và thay đổi chưa commit; không ghi đè công việc đang có.
3. Đọc lại contract/code/test liên quan đến mục đang làm trước khi sửa.
4. Chọn một mục nhỏ dưới đây; nêu phạm vi và tiêu chí hoàn thành, rồi code + test + đọc lại diff.
5. Cập nhật bằng chứng kiểm thử và bước tiếp theo; không gọi một mục hoàn thành chỉ vì đã tạo file.

Tài liệu này là bộ nhớ bàn giao trong repository, không phải cam kết rằng AI nhớ vĩnh viễn giữa mọi phiên.

## 2. Mục tiêu và giới hạn

- Hoàn thiện rộng và sâu phần Người 2: HTTP, assertions, variables/extraction/chaining, kết quả, persistence, RabbitMQ, concurrency/retry.
- Chỉ thay đổi worker; không tự sửa contract, schema, backend/UI hoặc tự bổ sung yêu cầu ngoài phân công.
- Code rõ trách nhiệm, tên dễ hiểu, hàm vừa đủ nhỏ; chỉ tách abstraction khi có trách nhiệm riêng thực sự. Không tạo framework/plugin/event bus cho đẹp hình thức.
- Giữ contract DTO tách model nội bộ, lỗi cấu hình tách lỗi transport, assertion FAIL tách test execution ERROR.
- Không tự làm sạch/đổi dữ liệu negative test thành dữ liệu hợp lệ. Chỉ từ chối cấu hình không thể xử lý hoặc vi phạm chính sách an toàn được công bố.
- Mỗi tính năng có happy path, boundary, failure, test tương tác khi cần, giới hạn rõ và ví dụ giải thích được khi bảo vệ.
- Không dùng số test làm bằng chứng duy nhất; không tự tuyên bố production-ready, hỗ trợ toàn bộ OpenAPI hoặc bảo mật đầy đủ.
- Chỉ commit khi người dùng yêu cầu; không tự push/merge; không thêm dependency nếu chưa kiểm tra stack, nhu cầu và tương thích.

## 3. Hiện trạng đã xác nhận

- Nhánh: `vinh`; A1/A2/B1/B2 đã triển khai bước đầu, B1/B2 chưa qua vòng rà soát dưới đây.
- Có 3 model, RequestUriBuilder, fixture request, 57 test pass ở lần chạy trước (40 URI, 15 model, 2 kiểm tra cấu trúc application).
- Chưa có HTTP runner, assertion, interpolation, persistence hoặc listener. Test application không chứng minh ứng dụng khởi động được.
- Java 21; Maven portable 3.9.9 ngoài repository, xem lệnh chạy ở README.
- Các file đang thay đổi chưa commit. Không coi chúng là thay đổi lạ cần xóa khi tiếp tục.

## 4. Rà soát trước khi viết B3

### R1 — URI đúng ngữ nghĩa, không quá hạn chế (đã rà soát ở mức builder)

Phát hiện ban đầu: `path.contains(":")` chặn path hợp lệ như `/users:search`. Đã sửa và có regression test.

- [x] Viết regression test chứng minh lỗi, sau đó thay điều kiện blanket bằng kiểm tra scheme/authority phù hợp.
- [x] Giữ chặn absolute URL/network-path và kiểm tra authority cuối không bị thay ngoài ý muốn.
- [x] Rà base URL: scheme, host, IPv6, port, raw path/query, credentials/fragment, biến chưa resolve; không lộ credentials trong thông báo lỗi.
- [x] Rà percent encoding: static path so với tham số raw, percent literal, Unicode, reserved characters, braces hỏng.
- [x] Xác định hành vi empty path parameter, dot segments (static/tham số/encoded), path ghép từ nhiều placeholder. Không gắn nhãn chống traversal đầy đủ khi chưa kiểm chứng trên wire/server.
- [x] Kiểm tra query key trùng base query, null trong array, boolean/number, empty key/value; ghi rõ quy tắc append/omit hiện có.
- [x] Giữ giới hạn query serialization rõ: scalar/repeated-key array, chưa có style/explode/deepObject. Không đổi contract để hỗ trợ tùy tiện.
- [x] Tách private methods validation/expansion/query nếu giúp đọc rõ; không chia quá nhiều class chỉ để giảm số dòng.

Bằng chứng: regression chạy trước sửa có 3 failures và 7 errors; sau sửa `clean test` toàn worker có 74 tests pass (57 URI). Dot segments/giá trị rỗng được giữ để phục vụ negative test, không normalize; phải kiểm chứng hành vi wire ở B3, không coi đây là chính sách chặn traversal. Lỗi parse base URL không giữ message/cause chứa URL gốc. Tiếp theo: R2.

Hoàn thành: regression tests có ý nghĩa, toàn bộ test worker pass, README phản ánh đúng hành vi và không tự nhận validation này là chống SSRF.

### R2 — Model, ownership và lỗi đầu vào (sau R1, trước B3)

- [x] Snapshot JsonNode chuẩn từ JSON parser ở constructor và accessor; regression mutate nested body/query. Không cam kết custom JsonNode/POJONode bọc object tùy ý.
- [x] Null map thành rỗng; null entry/list element báo IllegalArgumentException có tên trường, không lộ dữ liệu. Absent body và explicit JSON null round-trip khác nhau.
- [x] HttpResponseData phân biệt complete/transport/partial response; giữ thông tin đã nhận, không bịa status/body, latency không âm.
- [x] Resolved request kiểm tra method có mặt và URI HTTP(S); header syntax/token method để ranh giới client B3. Không sửa negative payload, không tự thêm auth/content type.
- [x] Fixture và round-trip pass; không thêm field wire contract.
- [x] ModelSnapshots package-local chia sẻ copy/kiểm tra map cho ba model, không thêm dependency.

Kết quả R2: 18 regression test mới; `clean test` tổng 92 tests pass, 0 failures/errors/skipped. Hành vi runner/wire vẫn chờ R3/B3.

Hoàn thành: ownership và error semantics được ghi rõ, regression tests pass, model vẫn dễ dùng và không đổi wire contract.

### R3 — Kế hoạch test HTTP và chốt thiết kế B3

- [ ] Chọn server test cục bộ tương thích stack hiện tại; chỉ thêm thư viện khi có lý do và kiểm tra version.
- [ ] Chọn API runner nhất quán với WebClient; không block Reactor event loop, không trộn reactive/blocking tùy tiện.
- [ ] Quy định xử lý method, JSON string vs raw text, absent body vs JSON null, headers case-insensitive/multiple, response charset, redirect, HEAD/204.
- [ ] Không tự follow redirect làm mất 3xx; nếu có tùy chọn phải rõ và không mang secret sang host khác.
- [ ] Có timeout nền an toàn cho test ngay từ B3; B4 làm đầy đủ phân loại timeout/network/partial response, không để test treo vì chờ đến B4.
- [ ] Test ghi nhận raw URI, method, headers và bytes/body thực nhận, không chỉ mock WebClient rồi kiểm tra invocation.

Hoàn thành: ma trận request/response và phương án test được ghi lại, sau đó mới triển khai B3 theo từng nhóm hành vi.

## 5. Các cổng rà soát khi mở rộng (không code hết cùng lúc)

### HTTP Runner và tài nguyên — B3/B4

- [ ] Method GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS theo khả năng contract; body scalar/array/object/null/absent, query/headers đúng trên wire.
- [ ] Giữ 2xx/3xx/4xx/5xx, text/JSON/rỗng; đo bằng monotonic clock đến khi body hoàn tất.
- [ ] Timeout toàn request và lỗi connect/read được phân loại; giải phóng response/buffer, kết thúc server/executor test.
- [ ] Giới hạn response và connection pool có cấu hình; không vô hiệu TLS validation để chạy demo.

### Assertions — C

- [ ] Mỗi rule có expected/actual/message rõ; test PASS/FAIL và malformed rule. Không catch mọi exception rồi báo FAIL chung chung.
- [ ] Status 400 expected/actual 400 là PASS; latency đúng toán tử và ngưỡng.
- [ ] JSONPath so sánh kiểu/số/null/missing nhất quán; không coi thiếu path tương đương JSON null khi rule cần phân biệt.
- [ ] Header không phân biệt hoa/thường, nhiều giá trị; contract HEADER chưa có payload đầy đủ nên cần chốt representation trước code.
- [ ] Networknt dialect rõ, schema không hợp lệ tách response không hợp lệ; không nhận là hỗ trợ mọi schema OpenAPI 3.0/3.1.
- [ ] Unsupported/empty rules có chính sách công khai; không âm thầm bỏ qua rule và kết luận PASS.

### Variables và chaining — D

- [ ] Substitution recursive giữ kiểu nếu toàn giá trị là biến, chuỗi ghép thì stringify theo quy tắc; missing variable không silently thành chuỗi rỗng.
- [ ] Context riêng theo job/chain, không sửa environment/template; extraction có precedence/overwrite policy.
- [ ] Test Create -> extract ID -> Get -> Delete; stop-on-first-failure/SKIPPED và bước lỗi vẫn có kết quả.
- [ ] Không chạy song song bước phụ thuộc chỉ vì có concurrency; contract orderIndex chưa mô tả graph độc lập.

### Kết quả, persistence và secret — E

- [ ] PASSED/FAILED/ERROR/SKIPPED, timestamps/latency, assertion details đủ truy vết; không log credential/body nhạy cảm mặc định.
- [ ] Redaction trên bản sao trước lưu/log; không sửa request thực gửi hoặc làm sai assertion/extraction.
- [ ] Mapping JSONB/FK đúng migration; test với PostgreSQL thực khi có, không giả vờ H2 chứng minh JSONB.
- [ ] Làm rõ xử lý idempotency/attempt trước khi kết hợp redelivery; không tự thay schema hoặc nhầm test-case ID với execution ID.
- [ ] Không lưu DB và publish để backend lưu cùng kết quả hai lần; tuân thủ phương án tích hợp nhóm đã chốt.

### RabbitMQ, concurrency, retry — F/G

- [ ] Job validation, malformed payload, ack/requeue và lỗi lưu kết quả có đường xử lý rõ; không poison-message loop hoặc ack trước khi kết quả bền vững.
- [ ] Chỉ nhận at-least-once khi chưa có cơ chế mạnh hơn; không tự nhận exactly-once.
- [ ] Giới hạn in-flight jobs/requests, tách listener concurrency khỏi setting trong job, kiểm tra isolation và shutdown khi còn việc.
- [ ] Retry hữu hạn, không retry assertion FAIL hoặc POST không an toàn một cách mù quáng; không cộng chồng retry client/broker mà không kiểm soát.
- [ ] Kiểm tra tích hợp broker redelivery và persistence failure khi có hạ tầng; thiếu thì ghi BLOCKED.

### An toàn và demo cuối

- [ ] Chính sách target URL/SSRF cấu hình phù hợp demo nội bộ; không mặc định chặn mọi private IP hoặc cho phép toàn bộ địa chỉ mà không cảnh báo.
- [ ] Kiểm soát redirect/DNS/credentials và tải schema bên ngoài theo khả năng thư viện; document giới hạn thay vì tuyên bố an toàn tuyệt đối.
- [ ] Giới hạn số test, response, timeout/concurrency; lỗi cấu hình phải dễ hiểu.
- [ ] README chạy được trên máy khác; fixture không chứa secret thật; demo tái hiện được PASS/FAIL/ERROR/SKIPPED và chaining.
- [ ] Có bảng hỗ trợ/chưa hỗ trợ, sơ đồ luồng và giải thích các quyết định để bảo vệ đồ án.

## 6. Những quyết định chưa được tự ý coi là đã chốt

- Query style/explode và HEADER rule representation chưa đầy đủ trong contract.
- Extraction failure có ảnh hưởng status hay không; empty assertions; precedence biến và overwrite extraction.
- Unit thực thi/attempt/idempotency, redelivery và một nơi chịu trách nhiệm persist result.
- Cách chứng minh các test độc lập để chạy song song; không suy diễn dependency graph.
- Account/project permissions, lifecycle/cancel của backend nằm ngoài worker, cần phối hợp khi tích hợp.

Chi tiết nội bộ có thể tự chọn và ghi rationale. Nếu cần đổi wire contract/schema hoặc hành vi chung giữa thành viên, ghi điểm vướng cụ thể và yêu cầu thống nhất, không tự sửa.

## 7. Definition of Done cho một lượt

- Đúng phạm vi và contract; không có placeholder/TODO thay cho hành vi được hứa.
- Có test cho logic mới hoặc regression cho lỗi; assertions kiểm tra kết quả có ý nghĩa.
- Chạy test mục tiêu và toàn worker; phân biệt compile/unit/integration, ghi thật số failure/skipped/BLOCKED.
- Đọc lại mọi file mới/sửa; kiểm tra git diff/status, không credential hoặc thay đổi module ngoài ý muốn.
- Cập nhật README khi hành vi đổi và nhật ký checklist, chỉ định đúng một đầu việc tiếp theo.

## 8. Bàn giao lượt lập kế hoạch rà soát

- Chỉ thêm/cập nhật tài liệu, chưa sửa các lỗi/giới hạn B1–B2 và chưa viết B3.
- Điểm bắt đầu tiếp theo: **R3 — chốt thiết kế HTTP và ma trận test**; R1–R2 đã hoàn thành ở mức builder/model, chưa nhảy thẳng sang HTTP Runner.
- Mốc 57 test pass là baseline chức năng nhỏ, không phải bằng chứng toàn worker đã hoàn thiện.