import React from 'react';
import { Layers, PlayCircle, BarChart3, Database, ShieldCheck, GitBranch } from 'lucide-react';

export default function App() {
  return (
    <div style={{ maxWidth: '1200px', margin: '0 auto', padding: '40px 20px' }}>
      <header style={{ marginBottom: '40px', borderBottom: '1px solid var(--border-color)', paddingBottom: '24px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '8px' }}>
          <Layers color="#3b82f6" size={32} />
          <h1 style={{ fontSize: '28px', fontWeight: 700 }}>OpenAPI Automated Testing Platform</h1>
        </div>
        <p style={{ color: 'var(--text-muted)', fontSize: '16px' }}>
          Nền tảng tự động sinh và thực thi kịch bản kiểm thử REST API dựa trên đặc tả OpenAPI (D22 Capstone)
        </p>
      </header>

      {/* Grid 3 Người / 3 Modules */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '20px', marginBottom: '40px' }}>
        
        {/* Member 1 Card */}
        <div style={{ background: 'var(--bg-card)', padding: '24px', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <Database color="#60a5fa" size={24} />
            <h2 style={{ fontSize: '18px', fontWeight: 600 }}>Người 1 — OpenAPI & Test Gen</h2>
          </div>
          <p style={{ color: 'var(--text-muted)', fontSize: '14px', marginBottom: '16px', lineHeight: 1.6 }}>
            Phân tích OpenAPI 3.x JSON/YAML, sinh các bộ test case: Valid, Boundary, Negative, Type Error, Auth Check và quản lý Dependency Chaining.
          </p>
          <div style={{ display: 'inline-block', padding: '4px 10px', background: '#1e3a8a', color: '#93c5fd', borderRadius: '6px', fontSize: '12px', fontWeight: 500 }}>
            Output: TestCase DTO (Contract #1)
          </div>
        </div>

        {/* Member 2 Card */}
        <div style={{ background: 'var(--bg-card)', padding: '24px', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <PlayCircle color="#34d399" size={24} />
            <h2 style={{ fontSize: '18px', fontWeight: 600 }}>Người 2 — Execution Worker</h2>
          </div>
          <p style={{ color: 'var(--text-muted)', fontSize: '14px', marginBottom: '16px', lineHeight: 1.6 }}>
            Worker nhận Job từ RabbitMQ, thực thi HTTP request, Assertion Engine (Status code, JSON Schema validation, latency, JSONPath).
          </p>
          <div style={{ display: 'inline-block', padding: '4px 10px', background: '#064e3b', color: '#6ee7b7', borderRadius: '6px', fontSize: '12px', fontWeight: 500 }}>
            Output: TestResult DTO (Contract #2)
          </div>
        </div>

        {/* Member 3 Card */}
        <div style={{ background: 'var(--bg-card)', padding: '24px', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <BarChart3 color="#f472b6" size={24} />
            <h2 style={{ fontSize: '18px', fontWeight: 600 }}>Người 3 — Platform & Analytics</h2>
          </div>
          <p style={{ color: 'var(--text-muted)', fontSize: '14px', marginBottom: '16px', lineHeight: 1.6 }}>
            Quản lý Project, Môi trường (Dev/Staging/Prod), Biến & Secrets, điều phối Test Run, Dashboard phân tích lỗi và báo cáo P95/P99.
          </p>
          <div style={{ display: 'inline-block', padding: '4px 10px', background: '#831843', color: '#fbcfe8', borderRadius: '6px', fontSize: '12px', fontWeight: 500 }}>
            Core: Web UI & Test Orchestration
          </div>
        </div>
      </div>

      {/* Database & Migration Banner */}
      <div style={{ background: '#1e293b', border: '1px solid #334155', borderRadius: '12px', padding: '20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <ShieldCheck color="#10b981" size={28} />
          <div>
            <h3 style={{ fontSize: '16px', fontWeight: 600 }}>Flyway Database Migration Ready</h3>
            <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
              Phiên bản V1__init_schema.sql (12 bảng PostgreSQL, JSONB, UUID) đã sẵn sàng.
            </p>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#94a3b8', fontSize: '14px' }}>
          <GitBranch size={16} />
          <span>Branch: main</span>
        </div>
      </div>
    </div>
  );
}
