import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  AlertCircle,
  ArrowUpRight,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Database,
  ExternalLink,
  FlaskConical,
  LayoutDashboard,
  BarChart3,
  LoaderCircle,
  Play,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  X,
  XCircle,
  LogOut,
  LockKeyhole,
} from 'lucide-react';
import './App.css';

type ExperimentStatus =
  | 'RECOVERED'
  | 'FAILED'
  | 'RUNNING'
  | 'PENDING'
  | string;

interface Experiment {
  id: string;
  startedAt: string;
  finishedAt: string | null;
  type: string;
  target: string;
  status: ExperimentStatus;
  recoveryMillis: number | null;
  detail: string | null;
}

interface ExperimentPage {
  page: number;
  size: number;
  total: number;
  items: Experiment[];
}

interface AnalyticsType { type: string; total: number; passed: number; failed: number; avgMillis: number; }
interface AnalyticsPoint { id: string; startedAt: string; type: string; status: string; durationMillis: number; }
interface AnalyticsSummary {
  total: number; passed: number; failed: number;
  podRecoveryAvgMillis: number | null;
  httpResponseAvgMillis: number | null;
  byType: AnalyticsType[];
  recent: AnalyticsPoint[];
}

interface ApiHealth {
  status: string;
}

interface ReliabilityEvaluation {
  id: string; startedAt: string; type: string; target: string; status: string;
  durationMillis: number; detail: string; thresholdMillis: number | null;
  verdict: 'PASSED' | 'FAILED' | 'UNSUPPORTED';
}
interface ReliabilityReport {
  policy: { podRecoveryThresholdMillis: number; httpResponseThresholdMillis: number };
  total: number; passed: number; failed: number; unsupported: number;
  passRatePercent: number | null; interpretation: string;
  evaluations: ReliabilityEvaluation[];
}
// PHASE9B_RELIABILITY
 type View = 'overview' | 'experiments' | 'observability' | 'analytics' | 'reliability';

const PAGE_SIZE = 20;
const API_BASE = '/api/v1';

const EMPTY_PAGE: ExperimentPage = {
  page: 0,
  size: PAGE_SIZE,
  total: 0,
  items: [],
};

let activeAuthorization: string | null = null;

async function requestJson<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const response = await fetch(path, {
    cache: 'no-store',
    ...options,
    headers: { ...(options?.headers ?? {}), ...(activeAuthorization ? { Authorization: activeAuthorization } : {}) },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(
      `HTTP ${response.status}${message ? `: ${message.slice(0, 180)}` : ''}`,
    );
  }

  return response.json() as Promise<T>;
}

function formatDate(value: string | null): string {
  if (!value) return '—';

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) return '—';

  return new Intl.DateTimeFormat('en-GB', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function formatDuration(milliseconds: number | null): string {
  if (milliseconds === null || milliseconds < 0) return '—';

  if (milliseconds < 1000) {
    return `${milliseconds} ms`;
  }

  return `${(milliseconds / 1000).toFixed(2)} s`;
}

function statusClass(status: string): string {
  if (status === 'RECOVERED' || status === 'HEALTHY' || status === 'PASSED') return 'status-success';
  if (status === 'FAILED') return 'status-danger';
  if (status === 'RUNNING') return 'status-running';

  return 'status-neutral';
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`status-badge ${statusClass(status)}`}>
      <span className="status-dot" />
      {status}
    </span>
  );
}

function StatCard({
  label,
  value,
  hint,
  icon: Icon,
}: {
  label: string;
  value: string | number;
  hint: string;
  icon: typeof Activity;
}) {
  return (
    <article className="stat-card">
      <div className="stat-top">
        <span>{label}</span>
        <Icon size={19} strokeWidth={1.8} />
      </div>
      <strong>{value}</strong>
      <small>{hint}</small>
    </article>
  );
}

function App() {
  const [auth, setAuth] = useState<{ username: string; role: 'ADMIN' | 'VIEWER' } | null>(null);
  const [loginUsername, setLoginUsername] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginError, setLoginError] = useState<string | null>(null);
  const [loginLoading, setLoginLoading] = useState(false);
  const [securityRequired, setSecurityRequired] = useState<boolean | null>(null);

  useEffect(() => {
    // Fail closed: the Phase 10D console requires a server-verified identity.
    // A browser may reuse cached HTTP Basic credentials on an initial request,
    // so a successful GET must NEVER be interpreted as security being disabled.
    setSecurityRequired(true);
  }, []);

  async function login(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoginLoading(true); setLoginError(null);
    try {
      const bytes = new TextEncoder().encode(`${loginUsername}:${loginPassword}`);
      let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte);
      const token = `Basic ${btoa(binary)}`;
      const response = await fetch(`${API_BASE}/auth/me`, {
        headers: { Authorization: token }, cache: 'no-store',
      });
      if (!response.ok) throw new Error(response.status === 401 ? 'Invalid credentials.' : `Authentication failed (HTTP ${response.status}).`);
      const identity: { username: string; role: string } = await response.json();
      if (!identity.username || !['ADMIN', 'VIEWER'].includes(identity.role)) throw new Error('Server returned an invalid identity.');
      activeAuthorization = token;
      setAuth({ username: identity.username, role: identity.role as 'ADMIN' | 'VIEWER' });
      setLoginPassword(''); setSecurityRequired(true);
    } catch (cause) { setLoginError(cause instanceof Error ? cause.message : 'Login failed'); }
    finally { setLoginLoading(false); }
  }

  function logout() {
    activeAuthorization = null; setAuth(null); setLoginPassword('');
    setHistory(EMPTY_PAGE); setAnalytics(null); setReliability(null);
    setConfirmOpen(false); setSelected(null); setExecutionResult(null);
  }
  const [view, setView] = useState<View>('overview');
  const [analytics, setAnalytics] = useState<AnalyticsSummary | null>(null);
  const [analyticsError, setAnalyticsError] = useState<string | null>(null);
  const [podThreshold, setPodThreshold] = useState(20000);
  const [httpThreshold, setHttpThreshold] = useState(800);
  const [reliability, setReliability] = useState<ReliabilityReport | null>(null);
  const [reliabilityError, setReliabilityError] = useState<string | null>(null);
  const [reliabilityLoading, setReliabilityLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [history, setHistory] = useState<ExperimentPage>(EMPTY_PAGE);
  const [apiStatus, setApiStatus] = useState<'UP' | 'DOWN' | 'CHECKING'>(
    'CHECKING',
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [selected, setSelected] = useState<Experiment | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [experimentType, setExperimentType] = useState('POD_TERMINATION');
  const [latencyMillis, setLatencyMillis] = useState(500);
  const [running, setRunning] = useState(false);
  const [executionResult, setExecutionResult] = useState<Experiment | null>(
    null,
  );

  const loadReliability = useCallback(async () => {
    if (!Number.isInteger(podThreshold) || !Number.isInteger(httpThreshold) ||
        podThreshold < 1 || httpThreshold < 1 || podThreshold > 600000 || httpThreshold > 600000) {
      setReliabilityError('Thresholds must be whole numbers between 1 and 600000 ms.');
      return;
    }
    setReliabilityLoading(true);
    setReliabilityError(null);
    try {
      const query = new URLSearchParams({
        podRecoveryThresholdMillis: String(podThreshold),
        httpResponseThresholdMillis: String(httpThreshold),
      });
      setReliability(await requestJson<ReliabilityReport>(`${API_BASE}/reliability/report?${query}`));
    } catch (cause) {
      setReliabilityError(cause instanceof Error ? cause.message : 'Reliability report unavailable');
    } finally { setReliabilityLoading(false); }
  }, [podThreshold, httpThreshold]);

  useEffect(() => {
    if (securityRequired && !auth) return;
    if (view === 'reliability' && !reliability) void loadReliability();
  }, [view, reliability, loadReliability, securityRequired, auth]);

  const loadData = useCallback(async () => {
    if (securityRequired === null || (securityRequired && !auth)) return;
    setLoading(true);
    setError(null);
    setAnalyticsError(null);

    const [healthResult, historyResult] = await Promise.allSettled([
      requestJson<ApiHealth>('/actuator/health'),
      requestJson<ExperimentPage>(
        `${API_BASE}/experiments/page?page=${page}&size=${PAGE_SIZE}`,
      ),
    ]);

    if (healthResult.status === 'fulfilled') {
      setApiStatus(healthResult.value.status === 'UP' ? 'UP' : 'DOWN');
    } else {
      setApiStatus('DOWN');
    }

    if (historyResult.status === 'fulfilled') {
      setHistory(historyResult.value);
    } else {
      setError(
        historyResult.reason instanceof Error
          ? historyResult.reason.message
          : 'Unable to load experiment history.',
      );
    }

    try {
      setAnalytics(await requestJson<AnalyticsSummary>(`${API_BASE}/analytics/summary`));
    } catch (cause) {
      setAnalyticsError(cause instanceof Error ? cause.message : 'Analytics unavailable');
    }
    setLoading(false);
  }, [page, securityRequired, auth]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const filteredItems = useMemo(() => {
    const query = search.trim().toLowerCase();

    return history.items.filter((experiment) => {
      const matchesStatus =
        filter === 'ALL' || experiment.status === filter;

      const matchesSearch =
        !query ||
        [
          experiment.id,
          experiment.type,
          experiment.target,
          experiment.status,
        ]
          .join(' ')
          .toLowerCase()
          .includes(query);

      return matchesStatus && matchesSearch;
    });
  }, [history.items, search, filter]);

  // Statistics describe the currently loaded page, not all historical
  // experiments. Global totals require a dedicated aggregate API.
  const recovered = history.items.filter(
    (experiment) => experiment.status === 'RECOVERED',
  ).length;

  const failed = history.items.filter(
    (experiment) => experiment.status === 'FAILED',
  ).length;

  const recoveryValues = history.items
    .filter(
      (experiment) =>
        experiment.status === 'RECOVERED' &&
        experiment.type === 'POD_TERMINATION' &&
        experiment.recoveryMillis !== null,
    )
    .map((experiment) => experiment.recoveryMillis as number);

  const averageRecovery =
    recoveryValues.length > 0
      ? recoveryValues.reduce((sum, value) => sum + value, 0) /
        recoveryValues.length
      : null;

  const pageCount = Math.max(
    1,
    Math.ceil(history.total / PAGE_SIZE),
  );

  async function executeExperiment() {
    if (auth?.role !== 'ADMIN') return;
    setRunning(true);
    setError(null);
    setExecutionResult(null);

    try {
      const result = await requestJson<Experiment>(
        `${API_BASE}/experiments/pod-termination`,
        {
          method: 'POST',
          headers: {
            'X-StormOps-Confirmation': 'TERMINATE_STORMOPS_DEMO_POD',
          },
        },
      );

      setExecutionResult(result);
      setConfirmOpen(false);
      setPage(0);
      await loadData();
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : 'The experiment could not be completed.',
      );
    } finally {
      setRunning(false);
    }
  }

  async function executeHttpExperiment() {
    if (auth?.role !== 'ADMIN') return;
    setRunning(true);
    setError(null);
    setExecutionResult(null);

    try {
      const result = await requestJson<Experiment>(
        `${API_BASE}/experiments/http-simulation`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-StormOps-Confirmation': 'RUN_STORMOPS_HTTP_SIMULATION',
          },
          body: JSON.stringify({
            type: experimentType,
            latencyMillis: experimentType === 'HTTP_LATENCY' ? latencyMillis : 0,
          }),
        },
      );
      setExecutionResult(result);
      setConfirmOpen(false);
      setPage(0);
      await loadData();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'HTTP experiment failed.');
    } finally {
      setRunning(false);
    }
  }

  function renderHistory() {
    return (
      <section className="panel history-panel">
        <div className="panel-heading">
          <div>
            <h2>Experiment history</h2>
            <p>
              Recorded chaos experiments and Kubernetes recovery outcomes
            </p>
          </div>
          <span className="muted-count">
            {history.total} total records
          </span>
        </div>

        <div className="toolbar">
          <label className="search-field">
            <Search size={17} />
            <input
              aria-label="Search experiments"
              placeholder="Search experiments..."
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>

          <select
            aria-label="Filter by status"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          >
            <option value="ALL">All statuses</option>
            <option value="RECOVERED">Recovered</option>
            <option value="FAILED">Failed</option>
            <option value="RUNNING">Running</option>
            <option value="PENDING">Pending</option>
          </select>
        </div>

        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Experiment</th>
                <th>Target</th>
                <th>Status</th>
                <th>Recovery</th>
                <th>Started</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filteredItems.map((experiment) => (
                <tr key={experiment.id}>
                  <td>
                    <span className="table-primary">
                      {experiment.type.replaceAll('_', ' ')}
                    </span>
                    <span className="table-secondary mono">
                      {experiment.id.slice(0, 8)}
                    </span>
                  </td>
                  <td className="target-cell">{experiment.target}</td>
                  <td>
                    <StatusBadge status={experiment.status} />
                  </td>
                  <td>{formatDuration(experiment.recoveryMillis)}</td>
                  <td>{formatDate(experiment.startedAt)}</td>
                  <td>
                    <button
                      className="icon-button"
                      aria-label={`View experiment ${experiment.id}`}
                      onClick={() => setSelected(experiment)}
                    >
                      <ArrowUpRight size={17} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {!loading && filteredItems.length === 0 && (
            <div className="empty-state">
              <FlaskConical size={30} />
              <strong>No matching experiments</strong>
              <p>
                Run a controlled experiment or adjust your filters.
              </p>
            </div>
          )}
        </div>

        <div className="table-footer">
          <span>
            Page {page + 1} of {pageCount}
            {search || filter !== 'ALL'
              ? ' · Filters apply to this page'
              : ''}
          </span>
          <div className="pagination">
            <button
              className="icon-button"
              aria-label="Previous page"
              disabled={page === 0 || loading}
              onClick={() => setPage((current) => current - 1)}
            >
              <ChevronLeft size={18} />
            </button>
            <button
              className="icon-button"
              aria-label="Next page"
              disabled={page + 1 >= pageCount || loading}
              onClick={() => setPage((current) => current + 1)}
            >
              <ChevronRight size={18} />
            </button>
          </div>
        </div>
      </section>
    );
  }

  if (securityRequired && !auth) return (
    <div className="stormops-login-screen">
      <form className="stormops-login-card" onSubmit={login}>
        <div className="stormops-login-icon"><LockKeyhole size={27}/></div>
        <h1>StormOps</h1><p>Sign in to the resilience console</p>
        <label>Username<input autoComplete="username" required value={loginUsername} onChange={e => setLoginUsername(e.target.value)} /></label>
        <label>Password<input type="password" autoComplete="current-password" required value={loginPassword} onChange={e => setLoginPassword(e.target.value)} /></label>
        {loginError && <div className="reliability-error" role="alert">{loginError}</div>}
        <button className="primary-button" disabled={loginLoading} type="submit">{loginLoading ? 'Signing in...' : 'Sign in'}</button>
        <small>Local development · HTTP Basic · use only on 127.0.0.1</small>
      </form>
    </div>
  );

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-symbol">
            <Activity size={23} strokeWidth={2.5} />
          </div>
          <div>
            <strong>StormOps</strong>
            <span>RESILIENCE PLATFORM</span>
          </div>
        </div>

        <div className="sidebar-label">WORKSPACE</div>

        <nav className="navigation" aria-label="Main navigation">
          <button
            className={view === 'overview' ? 'nav-active' : ''}
            onClick={() => setView('overview')}
          >
            <LayoutDashboard size={19} />
            Overview
          </button>
          <button
            className={view === 'experiments' ? 'nav-active' : ''}
            onClick={() => setView('experiments')}
          >
            <FlaskConical size={19} />
            Experiments
          </button>
          <button
            className={view === 'analytics' ? 'nav-active' : ''}
            onClick={() => setView('analytics')}
          >
            <BarChart3 size={19} />
            Analytics
          </button>
          <button
            className={view === 'reliability' ? 'nav-active' : ''}
            onClick={() => setView('reliability')}
          >
            <ShieldCheck size={19} />
            Reliability
          </button>
          <button
            className={view === 'observability' ? 'nav-active' : ''}
            onClick={() => setView('observability')}
          >
            <Activity size={19} />
            Observability
          </button>
        </nav>

        <div className="sidebar-bottom">
          {auth && <div className="stormops-session"><span>{auth.username} · {auth.role}</span><button onClick={logout} title="Sign out"><LogOut size={16}/> Sign out</button></div>}
          <div className="environment-card">
            <div className="environment-heading">
              <ShieldCheck size={17} />
              Isolated environment
            </div>
            <strong>stormops-lab</strong>
            <span>Local Kubernetes · kind</span>
          </div>
          <div className="sidebar-version">
            StormOps Console <span>v0.3 preview</span>
          </div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace <span>/</span>
            <strong>
              {view === 'overview'
                ? 'Overview'
                : view === 'experiments'
                  ? 'Experiments'
                  : view === 'analytics' ? 'Analytics'
                  : view === 'reliability' ? 'Reliability'
                  : 'Observability'}
            </strong>
          </div>
          <div className="topbar-actions">
            <span
              className={`connection-pill ${
                apiStatus === 'UP' ? 'connected' : 'disconnected'
              }`}
            >
              <span className="status-dot" />
              API {apiStatus}
            </span>
            <button
              className="icon-button"
              aria-label="Refresh data"
              disabled={loading}
              onClick={() => void loadData()}
            >
              <RefreshCw size={18} className={loading ? 'spin' : ''} />
            </button>
          </div>
        </header>

        <div className="content-container">
          <div className="page-heading">
            <div>
              <div className="eyebrow">
                <span className="eyebrow-line" />
                CHAOS ENGINEERING
              </div>
              <h1>
                {view === 'overview'
                  ? 'Resilience overview'
                  : view === 'experiments'
                    ? 'Experiments'
                    : view === 'analytics' ? 'Resilience analytics'
                    : view === 'reliability' ? 'Reliability engineering'
                    : 'Observability'}
              </h1>
              <p>
                {view === 'overview'
                  ? 'Understand how your services behave under controlled failure.'
                  : view === 'experiments'
                    ? 'Explore experiment outcomes and recovery history.'
                    : view === 'analytics' ? 'Compare HTTP response time and Kubernetes pod recovery without mixing metrics.'
                    : view === 'reliability' ? 'Evaluate historical experiments against resilience thresholds.'
                    : 'Inspect monitoring tools and service health.'}
              </p>
            </div>

            {view !== 'observability' && auth?.role === 'ADMIN' && (
              <button
                className="primary-button"
                disabled={apiStatus !== 'UP' || running}
                onClick={() => setConfirmOpen(true)}
              >
                <Play size={17} fill="currentColor" />
                Run experiment
              </button>
            )}
          </div>

          {error && (
            <div className="error-banner" role="alert">
              <AlertCircle size={19} />
              <span>{error}</span>
              <button
                className="icon-button"
                aria-label="Dismiss error"
                onClick={() => setError(null)}
              >
                <X size={17} />
              </button>
            </div>
          )}

          {executionResult && (
            <div className="success-banner">
              <CheckCircle2 size={19} />
              <span>
                Experiment {executionResult.id.slice(0, 8)} completed with
                status {executionResult.status}.
              </span>
              <button
                className="text-button"
                onClick={() => setSelected(executionResult)}
              >
                View details
              </button>
            </div>
          )}

          {view === 'overview' && (
            <>
              <div className="stats-grid">
                <StatCard
                  label="Total experiments"
                  value={history.total}
                  hint="All stored experiment records"
                  icon={FlaskConical}
                />
                <StatCard
                  label="Recovered"
                  value={analytics?.passed ?? recovered}
                  hint="All history · expected outcome observed"
                  icon={CheckCircle2}
                />
                <StatCard
                  label="Failed"
                  value={analytics?.failed ?? failed}
                  hint="All history · failed experiments"
                  icon={XCircle}
                />
                <StatCard
                  label="Avg. recovery"
                  value={formatDuration(analytics?.podRecoveryAvgMillis ?? averageRecovery)}
                  hint="Pod termination only · all history"
                  icon={Clock3}
                />
              </div>

              <div className="overview-grid">
                <section className="panel spotlight-panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Controlled fault injection</h2>
                      <p>Test recovery in a restricted Kubernetes lab</p>
                    </div>
                    <ShieldCheck size={21} />
                  </div>
                  <div className="spotlight-body">
                    <div className="fault-icon">
                      <Server size={25} />
                    </div>
                    <div>
                      <strong>Pod termination</strong>
                      <p>
                        Delete one demo-service pod and observe Kubernetes
                        restoring the desired replica count.
                      </p>
                      <span className="scope-label">
                        TARGET: stormops-demo · NAMESPACE: stormops-lab
                      </span>
                    </div>
                  </div>
                  <div className="spotlight-footer">
                    <span>
                      <ShieldCheck size={16} />
                      Explicit confirmation required
                    </span>
                    {auth?.role === 'ADMIN' && (
                      <button
                        className="secondary-button"
                        disabled={apiStatus !== 'UP'}
                        onClick={() => setConfirmOpen(true)}
                      >
                        Configure test <ArrowUpRight size={16} />
                      </button>
                    )}
                  </div>
                </section>

                <section className="panel health-panel">
                  <div className="panel-heading">
                    <div>
                      <h2>System connections</h2>
                      <p>Local development services</p>
                    </div>
                  </div>
                  <div className="health-row">
                    <span className="health-icon"><Server size={18} /></span>
                    <div>
                      <strong>Spring Boot API</strong>
                      <small>localhost:18080</small>
                    </div>
                    <StatusBadge
                      status={apiStatus === 'UP' ? 'HEALTHY' : 'FAILED'}
                    />
                  </div>
                  <div className="health-row">
                    <span className="health-icon"><Database size={18} /></span>
                    <div>
                      <strong>PostgreSQL</strong>
                      <small>Persistence via backend API</small>
                    </div>
                    <span className="health-note">
                      {error ? 'Unknown' : 'API-backed'}
                    </span>
                  </div>
                  <div className="health-row">
                    <span className="health-icon"><Activity size={18} /></span>
                    <div>
                      <strong>Grafana</strong>
                      <small>Local monitoring dashboard</small>
                    </div>
                    <a
                      className="external-link"
                      href="http://127.0.0.1:13000"
                      target="_blank"
                      rel="noreferrer"
                    >
                      Open <ExternalLink size={14} />
                    </a>
                  </div>
                </section>
              </div>

              {renderHistory()}
            </>
          )}

          {view === 'experiments' && renderHistory()}

          {view === 'analytics' && (
            <div className="analytics-stack">
              {analyticsError && <section className="panel analytics-panel">Analytics API: {analyticsError}. Restart the StormOps backend after installation.</section>}
              {analytics && <>
                <div className="stats-grid">
                  <StatCard label="Experiments" value={analytics.total} hint="All stored experiments" icon={FlaskConical} />
                  <StatCard label="Expected outcomes" value={analytics.passed} hint="Legacy RECOVERED records; not all pod recoveries" icon={CheckCircle2} />
                  <StatCard label="Pod recovery avg" value={formatDuration(analytics.podRecoveryAvgMillis)} hint="Kubernetes pod replacement only" icon={Server} />
                  <StatCard label="HTTP response avg" value={formatDuration(analytics.httpResponseAvgMillis)} hint="Baseline, latency and HTTP error requests" icon={Clock3} />
                </div>
                <section className="panel analytics-panel">
                  <div className="panel-heading"><div><h2>Experiments by scenario</h2><p>Counts across the complete PostgreSQL history</p></div></div>
                  <div className="analytics-bars">
                    {analytics.byType.map((item) => <div className="analytics-bar-row" key={item.type}>
                      <span>{item.type.replaceAll('_', ' ')}</span>
                      <div className="analytics-track"><div style={{ width: `${analytics.total ? item.total / analytics.total * 100 : 0}%` }} /></div>
                      <strong>{item.total}</strong>
                    </div>)}
                    {analytics.byType.length === 0 && <p>No experiments recorded yet.</p>}
                  </div>
                </section>
                <section className="panel analytics-panel">
                  <div className="panel-heading"><div><h2>Recent experiment durations</h2><p>Latest 30 experiments · milliseconds · compare within the same scenario only</p></div></div>
                  <div className="analytics-chart-scroll"><svg viewBox="0 0 900 260" role="img" aria-label="Recent experiment duration chart">
                    {[0,1,2,3,4].map((n) => <line key={n} x1="48" y1={20+n*48} x2="875" y2={20+n*48} stroke="#314154" strokeDasharray="4 5" />)}
                    {analytics.recent.slice(0,30).reverse().map((point,i,items) => {
                      const max = Math.max(1,...items.map(p => p.durationMillis));
                      const width = Math.min(22, 780 / Math.max(1, items.length) - 5);
                      const x = 55 + i * (800 / Math.max(1,items.length));
                      const h = 205 * point.durationMillis / max;
                      return <rect key={point.id} x={x} y={225-h} width={Math.max(3,width)} height={Math.max(2,h)} rx="3" fill={point.type === 'POD_TERMINATION' ? '#30c7bc' : '#d2a86d'}><title>{`${point.type}: ${point.durationMillis} ms`}</title></rect>;
                    })}
                    <text x="48" y="250" fontSize="12" fill="#9aacc0">Older</text><text x="830" y="250" fontSize="12" fill="#9aacc0">Newer</text>
                  </svg></div>
                  <p className="analytics-footnote">Teal = pod recovery · Gold = HTTP response duration. The vertical scale adapts to the largest recorded value; this chart is not an HTTP outage measurement.</p>
                </section>
              </>}
            </div>
          )}

          {view === 'reliability' && (
            <div className="analytics-stack">
              <section className="panel analytics-panel">
                <div className="panel-heading"><div><h2>Reliability policy</h2><p>Evaluate stored experiments against configurable duration thresholds. No faults are injected.</p></div></div>
                <div className="reliability-policy">
                  <label>Pod recovery threshold (ms)
                    <input type="number" min="1" max="600000" step="1" value={podThreshold}
                      onChange={e => setPodThreshold(Number(e.target.value))} />
                  </label>
                  <label>HTTP response threshold (ms)
                    <input type="number" min="1" max="600000" step="1" value={httpThreshold}
                      onChange={e => setHttpThreshold(Number(e.target.value))} />
                  </label>
                  <button className="primary-button" disabled={reliabilityLoading} onClick={() => void loadReliability()}>
                    {reliabilityLoading ? 'Evaluating…' : 'Evaluate policy'}
                  </button>
                  <button className="secondary-button" disabled={!reliability} onClick={() => {
                    if (!reliability) return;
                    const blob = new Blob([JSON.stringify(reliability, null, 2)], {type:'application/json'});
                    const url = URL.createObjectURL(blob);
                    const link = document.createElement('a'); link.href = url; link.download = 'stormops-reliability-report.json'; link.click();
                    setTimeout(() => URL.revokeObjectURL(url), 1000);
                  }}>Export JSON</button>
                </div>
                {reliabilityError && <p className="reliability-error" role="alert">{reliabilityError}</p>}
                <p className="analytics-footnote">Changes are applied only when you select Evaluate policy. Historical records remain unchanged.</p>
              </section>
              {reliability && <>
                <div className="stats-grid">
                  <StatCard label="Evaluated" value={reliability.total} hint="Historical experiments" icon={FlaskConical}/>
                  <StatCard label="Passed" value={reliability.passed} hint="Met expected result and threshold" icon={CheckCircle2}/>
                  <StatCard label="Failed" value={reliability.failed} hint="Did not meet the policy" icon={XCircle}/>
                  <StatCard label="Pass rate" value={reliability.passRatePercent === null ? 'N/A' : `${reliability.passRatePercent}%`} hint={`${reliability.unsupported} unsupported excluded`} icon={ShieldCheck}/>
                </div>
                <section className="panel analytics-panel">
                  <div className="panel-heading"><div><h2>Experiment assertions</h2><p>Measured duration compared with the active policy</p></div></div>
                  <div className="reliability-table-scroll"><table className="reliability-table"><thead><tr><th>Scenario</th><th>Duration</th><th>Threshold</th><th>Verdict</th></tr></thead><tbody>
                    {reliability.evaluations.map(item => <tr key={item.id}>
                      <td><strong>{item.type.replaceAll('_',' ')}</strong><small>{item.id.slice(0,8)} · {new Date(item.startedAt).toLocaleString()}</small></td>
                      <td>{item.durationMillis.toLocaleString()} ms</td>
                      <td>{item.thresholdMillis === null ? '—' : `${item.thresholdMillis.toLocaleString()} ms`}</td>
                      <td><span className={`reliability-verdict reliability-${item.verdict.toLowerCase()}`}>{item.verdict}</span></td>
                    </tr>)}
                  </tbody></table></div>
                  {reliability.evaluations.length === 0 && <p>No experiments recorded yet.</p>}
                  <p className="analytics-footnote">{reliability.interpretation} A simulated HTTP 503 can pass when it is the expected outcome.</p>
                </section>
              </>}
            </div>
          )}

          {view === 'observability' && (
            <div className="observability-grid">
              <section className="panel observability-card">
                <Activity size={27} />
                <h2>Grafana dashboards</h2>
                <p>
                  Open the existing StormOps Resilience Overview to
                  inspect Prometheus metrics and JVM telemetry.
                </p>
                <a
                  className="secondary-button"
                  href="http://127.0.0.1:13000"
                  target="_blank"
                  rel="noreferrer"
                >
                  Open Grafana <ExternalLink size={16} />
                </a>
              </section>

              <section className="panel observability-card">
                <Server size={27} />
                <h2>Backend health</h2>
                <p>
                  The console checks Spring Boot Actuator. This is not
                  an end-to-end service availability measurement.
                </p>
                <StatusBadge
                  status={apiStatus === 'UP' ? 'HEALTHY' : 'FAILED'}
                />
              </section>
            </div>
          )}

          <footer className="app-footer">
            <span>StormOps · Chaos Engineering & Resilience Platform</span>
            <span>Local development environment</span>
          </footer>
        </div>
      </main>

      {selected && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) {
              setSelected(null);
            }
          }}
        >
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label="Experiment details"
          >
            <div className="modal-header">
              <div>
                <span className="eyebrow">EXPERIMENT DETAILS</span>
                <h2>{selected.type.replaceAll('_', ' ')}</h2>
              </div>
              <button
                className="icon-button"
                aria-label="Close details"
                onClick={() => setSelected(null)}
              >
                <X size={20} />
              </button>
            </div>
            <StatusBadge status={selected.status} />
            <dl className="detail-list">
              <div><dt>Experiment ID</dt><dd className="mono">{selected.id}</dd></div>
              <div><dt>Target pod</dt><dd>{selected.target}</dd></div>
              <div><dt>Started</dt><dd>{formatDate(selected.startedAt)}</dd></div>
              <div><dt>Finished</dt><dd>{formatDate(selected.finishedAt)}</dd></div>
              <div><dt>{selected.type === 'POD_TERMINATION' ? 'Replica recovery' : 'HTTP request duration'}</dt><dd>{formatDuration(selected.recoveryMillis)}</dd></div>
              <div><dt>Details</dt><dd>{selected.detail || '—'}</dd></div>
            </dl>
            <p className="modal-note">
              {selected.type === 'POD_TERMINATION'
                ? 'Recovery time measures restoration of desired Kubernetes replicas, not necessarily HTTP downtime.'
                : 'For HTTP simulations, the duration field measures request elapsed time, not Kubernetes recovery.'}
            </p>
          </section>
        </div>
      )}

      {confirmOpen && auth?.role === 'ADMIN' && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !running) {
              setConfirmOpen(false);
            }
          }}
        >
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label="Confirm pod termination"
          >
            <div className="modal-header">
              <div>
                <span className="eyebrow">SAFETY CONFIRMATION</span>
                <h2>Run controlled experiment?</h2>
              </div>
              <button
                className="icon-button"
                disabled={running}
                aria-label="Cancel experiment"
                onClick={() => setConfirmOpen(false)}
              >
                <X size={20} />
              </button>
            </div>

            <p className="modal-description">
              {experimentType === 'POD_TERMINATION'
                ? 'This will delete one demo pod inside the StormOps Kubernetes lab. Kubernetes should replace it automatically.'
                : 'This will send one bounded HTTP request to the StormOps demo service. No permanent fault configuration is applied.'}
            </p>

            <div className="simulation-options">
              <label htmlFor="stormops-experiment-type">Experiment type</label>
              <select
                id="stormops-experiment-type"
                value={experimentType}
                disabled={running}
                onChange={(event) => setExperimentType(event.target.value)}
              >
                <option value="POD_TERMINATION">Pod termination</option>
                <option value="BASELINE">HTTP baseline</option>
                <option value="HTTP_LATENCY">HTTP latency</option>
                <option value="HTTP_ERROR">HTTP error (expected 503)</option>
              </select>
              {experimentType === 'HTTP_LATENCY' && (
                <label htmlFor="stormops-latency">
                  Injected latency (0–2000 ms)
                  <input
                    id="stormops-latency"
                    type="number"
                    min={0}
                    max={2000}
                    step={100}
                    value={latencyMillis}
                    disabled={running}
                    onChange={(event) => setLatencyMillis(Number(event.target.value))}
                  />
                </label>
              )}
            </div>

            <div className="confirmation-scope">
              <div><span>Experiment</span><strong>{experimentType}</strong></div>
              <div><span>Namespace</span><strong>stormops-lab</strong></div>
              <div><span>Deployment</span><strong>stormops-demo</strong></div>
            </div>

            <div className="modal-actions">
              <button
                className="secondary-button"
                disabled={running}
                onClick={() => setConfirmOpen(false)}
              >
                Cancel
              </button>
              <button
                className="danger-button"
                disabled={running || (experimentType === 'HTTP_LATENCY' && (!Number.isInteger(latencyMillis) || latencyMillis < 0 || latencyMillis > 2000))}
                onClick={() => {
                  if (experimentType === 'POD_TERMINATION') {
                    void executeExperiment();
                  } else {
                    void executeHttpExperiment();
                  }
                }}
              >
                {running ? (
                  <LoaderCircle size={17} className="spin" />
                ) : (
                  <Play size={17} />
                )}
                {running ? 'Running experiment...' : 'Confirm and execute'}
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

export default App;
