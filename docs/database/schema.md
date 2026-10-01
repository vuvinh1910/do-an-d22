# Đặc tả chi tiết Cơ sở dữ liệu (PostgreSQL DDL)

Cơ sở dữ liệu sử dụng **PostgreSQL 16**. Toàn bộ migration sẽ được quản lý bằng **Flyway** (`backend/src/main/resources/db/migration/`).

---

## 1. Extension yêu cầu
```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```

---

## 2. Bảng người dùng và dự án

```sql
-- 1. Users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'USER',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Projects
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Project Members
CREATE TABLE project_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) DEFAULT 'MEMBER', -- OWNER, ADMIN, MEMBER, VIEWER
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, user_id)
);
```

---

## 3. Bảng Môi trường & Biến (Environments & Variables)

```sql
-- 4. Environments
CREATE TABLE environments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL, -- Development, Staging, Production
    base_url VARCHAR(500) NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 5. Environment Variables
CREATE TABLE environment_variables (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    environment_id UUID NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    key VARCHAR(255) NOT NULL,
    value TEXT NOT NULL,
    is_secret BOOLEAN DEFAULT FALSE, -- Nếu true, mã hóa hoặc ẩn ở frontend
    UNIQUE (environment_id, key)
);
```

---

## 4. Bảng OpenAPI & API Operations

```sql
-- 6. OpenAPI Specifications
CREATE TABLE api_specs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    version VARCHAR(50),
    raw_content TEXT NOT NULL,       -- Toàn văn OpenAPI spec (JSON/YAML)
    format VARCHAR(10) NOT NULL,      -- JSON hoặc YAML
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 7. API Operations (Được bóc tách từ Spec)
CREATE TABLE api_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    spec_id UUID NOT NULL REFERENCES api_specs(id) ON DELETE CASCADE,
    method VARCHAR(10) NOT NULL,     -- GET, POST, PUT, DELETE, PATCH, etc.
    path VARCHAR(500) NOT NULL,       -- /api/v1/users/{id}
    operation_id VARCHAR(255),
    summary TEXT,
    parameters JSONB,                 -- Query, Path, Header, Cookie params & schema
    request_schema JSONB,             -- JSON schema của requestBody
    responses_schema JSONB,           -- JSON schema của responses theo status code
    security JSONB,                   -- Yêu cầu Auth (Bearer, ApiKey, etc.)
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_operations_spec_method_path ON api_operations(spec_id, method, path);
```

---

## 5. Bảng Test Cases & Suites

```sql
-- 8. Test Suites
CREATE TABLE test_suites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 9. Test Cases
CREATE TABLE test_cases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id UUID REFERENCES test_suites(id) ON DELETE SET NULL,
    operation_id UUID REFERENCES api_operations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    test_type VARCHAR(50) NOT NULL,  -- VALID, BOUNDARY, NEGATIVE, MISSING_REQUIRED, TYPE_ERROR, AUTH
    
    -- Request payload template (có thể chứa biến {{userId}})
    request_template JSONB NOT NULL, -- { "headers": {}, "params": {}, "body": {} }
    
    -- Quy tắc kiểm tra kết quả
    assertion_rules JSONB NOT NULL,  -- [ { "type": "STATUS_CODE", "expected": 200 }, ... ]
    
    -- Quy tắc trích xuất dữ liệu dùng cho chaining
    extraction_rules JSONB,          -- [ { "variable": "token", "source": "BODY", "jsonPath": "$.data.token" } ]
    
    order_index INT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

---

## 6. Bảng Thực thi & Kết quả (Test Runs & Results)

```sql
-- 10. Test Runs
CREATE TABLE test_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    environment_id UUID NOT NULL REFERENCES environments(id),
    suite_id UUID REFERENCES test_suites(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL,     -- QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    total_tests INT DEFAULT 0,
    passed_tests INT DEFAULT 0,
    failed_tests INT DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 11. Test Results
CREATE TABLE test_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_run_id UUID NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    test_case_id UUID REFERENCES test_cases(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL,     -- PASSED, FAILED, ERROR, SKIPPED
    http_status INT,
    response_time_ms BIGINT,
    request_sent JSONB,              -- Request thực tế đã gửi (sau khi resolve biến)
    response_received JSONB,          -- Response thực tế nhận được (headers, body)
    assertion_details JSONB,         -- Chi tiết từng rule pass/fail
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_test_results_run_id ON test_results(test_run_id);
```

---

## 7. Bảng Hỗ trợ AI (Giai đoạn sau)

```sql
-- 12. AI Suggestions
CREATE TABLE ai_suggestions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id UUID NOT NULL REFERENCES api_operations(id) ON DELETE CASCADE,
    prompt_context TEXT,
    suggested_case JSONB NOT NULL,
    status VARCHAR(30) DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
    reviewed_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```
