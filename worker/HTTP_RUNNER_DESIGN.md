# R3 — Thiết kế HTTP Runner và ma trận kiểm thử

## Phạm vi và bằng chứng

Đây là quyết định triển khai nội bộ cho B3/B4, không thay đổi ba wire contract của nhóm.
Mốc B3/B4 đã hoàn thành trong phạm vi local HTTP, với 164 tests toàn worker; các giới hạn và bằng chứng ghi ở cuối tài liệu. `WebClientTransportProbeTest` là test thăm dò transport ban đầu với WebClient hiện có:
5 test đã chạy pass, quan sát raw URI, bytes body, Content-Type vắng mặt, 400/multi-value headers và redirect 302.
Không suy rộng kết quả này thành toàn bộ ma trận bên dưới đã được kiểm chứng.

## 1. Server và tài nguyên test

- Dùng `com.sun.net.httpserver.HttpServer` của JDK 21, bind `127.0.0.1`, port 0 (OS cấp).
- Không thêm Maven dependency, không Spring context, database, RabbitMQ hay API Internet.
- Mỗi test có server/executor riêng; finally/AfterEach đóng exchange, stop server, shutdown và await executor.
- Probe dùng `HttpClient.newConnection()` để không giữ connection pool. Reactor event loops dùng tài nguyên global của thư viện, không được test tùy tiện dispose vì có thể ảnh hưởng test khác; fork JVM kết thúc sẽ giải phóng chúng.
- Test có JUnit timeout 10s, response timeout 2s và block tối đa 3s ở test thread; production runner không block.
- Với lỗi wire bị cắt giữa chừng, B4 dùng TCP fixture riêng để kiểm soát chính xác response chưa hoàn chỉnh; không giả lập bằng việc gọi endpoint không tồn tại và coi đó là partial response.

## 2. API và ranh giới runner

API hiện có: `Mono<HttpResponseData> execute(ResolvedHttpRequest request, Duration timeout)` trên `WebClientHttpRunner`.

- Runner là concrete class nhỏ, chưa cần interface nhiều implementation. Inject ObjectMapper; runner sở hữu cấu hình client không redirect/retry, không nhận client tùy ý có auth/filter mặc định làm sai test.
- Timeout bắt buộc dương, dùng settings.timeoutMs khi lớp orchestration được triển khai; chưa tạo job DTO hoặc thêm config hiện tại.
- Validate input bằng lỗi cấu hình an toàn; không chuyển lỗi cấu hình thành response transport giả.
- Cold publisher: không có network I/O trước subscribe. Mỗi subscription là một lần thực thi, không cache result hoặc subscribe nội bộ.
- Không `block()`/`subscribe()` bên trong runner, không dùng Thread.sleep hoặc giữ mutable state chung giữa requests.
- Dùng `exchangeToMono`, không `retrieve()` với mặc định biến 4xx/5xx thành exception.
- Truyền URI overload, không String overload; không encode/normalize lần nữa. RequestUriBuilder chạy trước runner, interpolation nằm ở D.
- Dùng `System.nanoTime()` trong defer để đo elapsed đến khi body được consume hoặc lỗi, không dùng wall clock. Latency gồm connect/send/wait/read, không gồm assertion hoặc thời gian caller chờ trước subscribe.

## 3. Request semantics

- Dùng method được cung cấp, không sửa GET/DELETE thành POST hoặc bỏ body âm thầm. B3 kiểm chứng các method thường dùng; custom token/header không hợp lệ phải là lỗi có ngữ cảnh, không echo credential. CONNECT/upgrade không thuộc REST runner này.
- Jackson serialize JsonNode thành UTF-8 bytes rồi gửi bằng byte publisher. JSON TextNode `hello` gửi bytes `"hello"`, không biến thành raw `hello`. Raw text/binary/multipart chưa được contract model hỗ trợ.
- Body Java null: không body publisher; NullNode: gửi bốn bytes `null`. Không sửa sai kiểu trong payload.
- Giữ header nhiều giá trị và lookup theo HTTP case-insensitive, không dựa vào chữ hoa/thường của Map.copyOf. Gom các key khác casing qua HttpHeaders.add thay vì ghi đè.
- Không tự thêm Authorization, Accept, Content-Type hoặc cookie jar. Framing như Host/Content-Length do client tạo là bình thường, không đồng nghĩa byte-for-byte replay request.
- B3.1 từ chối mọi Host/Content-Length/Transfer-Encoding tùy chỉnh bằng lỗi cấu hình an toàn; client quản lý authority/framing. Không hỗ trợ test raw framing/Host override. Header name/method phải là token; value không có control characters trừ HTAB. Chưa hứa hỗ trợ arbitrary Unicode header.
- Tắt redirect, implicit retry (`disableRetry(true)`) và compression tự động. Không bỏ qua kiểm chứng TLS. Chưa có retry ứng dụng, proxy tùy chỉnh hay SSRF policy.

## 4. Response, giới hạn và lỗi

- 3xx/4xx/5xx là response HTTP bình thường, `error == null`; assertion engine quyết định PASSED/FAILED sau này.
- Giữ status và toàn bộ header values; cookie riêng biệt không gộp bằng dấu phẩy. Không parse body thành JSON tại runner.
- HEAD/204 hoặc response zero bytes -> body `""`, không `null`. Body đủ: decode theo charset Content-Type; thiếu charset dùng UTF-8. Đây là text view, không bảo toàn binary bytes; malformed/unsupported charset phải có test và quy tắc lỗi trước khi nhận hỗ trợ.
- Response cap mặc định 1 MiB; constructor `(ObjectMapper, int maxResponseBytes)` yêu cầu giới hạn dương. Aggregate raw DataBuffers qua DataBufferUtils.join với cap rồi copy/release; join chịu trách nhiệm release khi error/cancel. Vượt giới hạn giữ status/headers, body null, lỗi size limit; không cắt body âm thầm. Test hành vi không thay thế công cụ kiểm tra leak chuyên dụng.
- Deadline reactive bao phủ toàn exchange, không chỉ idle time giữa chunks; connect/response timeout cũng phải hữu hạn. Timeout caller và cancellation cần giải phóng subscription/connection.
- Trước khi nhận headers: lỗi transport có status/body null, headers rỗng. Sau headers: giữ status/headers, body null nếu aggregate chưa hoàn tất, error không rỗng. Không hứa giữ bytes từng phần khi đang aggregate.
- Dùng thông báo lỗi phân loại ổn định (timeout/connect/body read/limit), không copy exception message, URI, headers/body hoặc cause chứa secret vào kết quả/log.
- Trạng thái từng execution, kể cả headers đã nhận, phải nằm trong mỗi subscription, không field mutable dùng chung.

## 5. Ma trận và thứ tự B3/B4

| Nhóm | Cases cần chứng minh qua runner | Đợt |
|---|---|---|
| Lát cắt đầu | GET -> 200 text; status/headers/body/latency; cold publisher; không infrastructure ngoài loopback | B3.1 |
| Request bytes | POST object/array/string/number/bool/null/absent; UTF-8; missing auth/content type; negative body giữ nguyên | B3.2 |
| Method/header | PUT/PATCH/DELETE/OPTIONS/HEAD, repeated/case-insensitive header; header không hợp lệ/framing conflict | B3.2 |
| URI trên wire | %2F/%25, Unicode encoded, repeated query, base path/query; empty và dot segments qua builder -> runner | B3.2 |
| Response | 201/204/302/400/401/403/500; empty/text/invalid JSON; nhiều cookie; charset khai báo và fallback | B3.3 |
| Giới hạn nền | Deadline hữu hạn từ B3.1; body limit tại B3.3; kiểm tra release body/connection | B3 |
| Lỗi | Chậm trước headers, chậm body/chunks, connection refused, đóng kết nối, limit, decode failure; giữ headers đã nhận và sanitize errors | B4 |
| Isolation/lifecycle | Subscribe nhiều lần, request song song không lẫn state, cancel/timeout cleanup, không retry implicit | B4 |

Probe hiện tại chỉ xác minh một phần hành vi thư viện; B3 phải test thông qua API runner thật.
Không dùng sleep để đo chính xác millisecond: dùng latch khi điều phối, deadline đủ rộng, assert lower bound hợp lý cho delayed body thay vì equality latency.
Không coi 92 unit tests hoặc probe loopback là test TLS, SSRF, production load hay integration toàn Worker.

## Điểm bắt đầu tiếp theo

**B3.1 đã triển khai:** GET 200 trên loopback, raw target, UTF-8, multi-cookie, cold/repeated subscriptions, latency tới body completion và timeout trước headers/trong body. Timeout sau headers giữ status/headers và body null, không giả phần body chưa aggregate xong.

Runner chưa đăng ký Spring bean; caller/test tạo với ObjectMapper cấu hình xong, không thay đổi mapper khi đang dùng. Không connection pool; event loops dùng global Reactor resources. Kết quả lỗi đã sanitize nhưng chưa có chính sách log/redaction toàn ứng dụng; không bật wiretap/request DEBUG với secret thật. Timeout lấy từ caller và connect cap 10s, chưa cấu hình job orchestration. Serialize body trước subscribe là xử lý cục bộ, không nằm trong latency HTTP. Decode dùng charset khai báo hoặc UTF-8, REPORT malformed/unmappable bytes. Charset/Content-Type không hợp lệ với body có bytes -> decoding failed; body zero bytes -> chuỗi rỗng, không cần parse charset. Body là text view, không có binary result hoặc gzip decompression tự động.

**B3.2 đã kiểm chứng:** WebClientRequestWireTest có 24 tests qua server thật, capture method/raw target/headers/bytes rồi assert trên test thread. POST giữ JSON object/array/string/integer/decimal/boolean/null, Unicode và payload sai kiểu; absent body zero bytes, không tự thêm auth/content type. GET/POST/PUT/PATCH/DELETE/OPTIONS giữ body, HEAD không body và response rỗng. Header khác casing được gộp đủ giá trị (không cam kết thứ tự giữa key khác casing vì model dùng Map.copyOf).

Builder -> runner giữ base path/query, colon path, Unicode encoded, %2F/%25, repeated query, empty query value, empty/dot/encoded path params và static dot segments. Đây là quan sát với Reactor Netty/JDK server hiện tại, không bảo đảm proxy/server khác không normalize.

**B3.3 đã kiểm chứng:** 27 response wire tests: 200/201/302/400/401/403/500 giữ raw text kể cả invalid JSON; HEAD/204/empty; multi-cookie; redirect không follow; UTF-8/ISO-8859-1/UTF-16LE và fallback; malformed bytes/unsupported charset/Content-Type báo lỗi an toàn; default chunked cap 1 MiB ± 1 byte và custom fixed-length cap. Có bytes decode/limit lỗi vẫn giữ status/headers, không trả partial text giả.

**B4.1 đã kiểm chứng:** 7 TCP loopback tests cho connection refused, đóng trước/sau headers, không retry trong cửa sổ 600ms, deadline toàn exchange không reset theo chunks, cancellation trước/sau headers đóng socket/không phát result giả và subscribe lại, hai subscriptions cùng Mono cô lập status/headers/error. Targeted 7 tests và clean test parent + worker 162 tests pass. Chưa cần sửa production runner.

Fixture dùng read/accept timeout 5s, Future.get hữu hạn, close socket và shutdown/await executor. Test deadline dùng wall-clock bound rộng vẫn có thể nhạy với máy quá tải; refused-port có race tái sử dụng port nhỏ. Đây không phải load/leak detector hoặc test DNS/TLS/proxy/SSRF. Reactor Netty log WARN khi fixture đóng sớm; safe result error không bảo đảm transport logging được redaction.

## B4.2 — Mapping lỗi và logging policy

| Tình huống | Kết quả |
| --- | --- |
| Cấu hình không hợp lệ | IllegalArgumentException ngay, không gửi HTTP |
| Deadline toàn exchange | HTTP exchange timed out |
| Aggregate vượt byte cap | HTTP response exceeded size limit |
| Charset/media type/bytes text không hợp lệ | HTTP response decoding failed |
| Exception khác trước status | HTTP transport failed |
| Exception khác sau status | HTTP response read failed |
| Cancellation | Hủy upstream/socket, không tạo HttpResponseData |
| HTTP 4xx/5xx hoàn chỉnh | Giữ status/body, error null; assertion quyết định pass/fail |

Execution failure có body null, giữ status/headers nếu đã biết; không kèm raw exception/cause trong error string. DNS/TLS/connect failures thuộc transport nếu trước status, chưa có test riêng cho DNS/TLS. JVM fatal errors không được hứa chuyển thành result.

Positive control với Content-Length không hợp lệ chứng minh Netty WARN có raw header value. application.yml mặc định OFF cho reactor.netty, io.netty, org.springframework.web.reactive.function.client và org.springframework.http.client.reactive. Đây là suppression, không redaction: mất diagnostic logs cấp thấp, không bảo đảm trước mọi logger khác/custom hooks/external config. Không bật wiretap hoặc log request/response/model/exception với dữ liệu thật. Runner độc lập không nạp Boot YAML: caller nhúng ngoài ứng dụng phải áp dụng chính sách tương đương. Không tự thay logger toàn cục trong constructor.

HttpClientLoggingPolicyTest nạp YAML thật và dùng Boot LoggingSystem áp dụng/khôi phục mức logger; 2 cases WARN/OFF xác nhận marker giả lộ/không lộ trong console, error string luôn cố định. Không là full Spring startup test. DTO vẫn giữ dữ liệu gốc phục vụ assertion; trước khi persist/log cần redaction riêng ở E1. Logger override, child logger hoặc appenders tùy chỉnh có thể phá policy.

**Mốc B hoàn thành trong phạm vi local HTTP:** parent + worker clean test 164 tests pass. Không chứng nhận TLS/DNS/proxy/SSRF/load/leak/production security; không retry, bean wiring hoặc job lifecycle. Tiếp theo **C1 — assertion models/result và STATUS_CODE** theo contract, gồm expected 400/actual 400 phải pass.