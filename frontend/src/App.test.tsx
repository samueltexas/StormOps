import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from './App';

function jsonResponse(value: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => value, text: async () => '' } as Response;
}

function mockBackend(role: 'ADMIN' | 'VIEWER' | null) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/auth/me')) return role ? jsonResponse({ username: role.toLowerCase(), role }) : jsonResponse({}, 401);
    if (url.includes('/experiments/page')) return jsonResponse({ page: 0, size: 20, total: 0, items: [] });
    if (url.includes('/analytics/summary')) return jsonResponse({ total: 0, passed: 0, failed: 0, podRecoveryAvgMillis: null, httpResponseAvgMillis: null, byType: [], recent: [] });
    if (url.includes('/actuator/health')) return jsonResponse({ status: 'UP' });
    if (url.includes('/reliability/report')) return jsonResponse({ policy: { podRecoveryThresholdMillis: 20000, httpResponseThresholdMillis: 800 }, total: 0, passed: 0, failed: 0, unsupported: 0, passRatePercent: null, interpretation: '', evaluations: [] });
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

async function signIn(username: string) {
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: username } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-password' } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('StormOps access control', () => {
  beforeEach(() => vi.restoreAllMocks());
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('requires sign-in before displaying the console', async () => {
    const fetchMock = mockBackend(null);
    render(<App />);
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Run experiment' })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('rejects invalid credentials without opening the console', async () => {
    mockBackend(null);
    render(<App />);
    await signIn('viewer');
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid credentials');
    expect(screen.queryByRole('button', { name: 'Run experiment' })).not.toBeInTheDocument();
  });

  it('keeps experiment execution controls hidden for VIEWER', async () => {
    const fetchMock = mockBackend('VIEWER');
    render(<App />);
    await signIn('viewer');
    expect(await screen.findByText('viewer · VIEWER')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Run experiment' })).not.toBeInTheDocument();
    expect(screen.queryByText('Configure test')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes('/pod-termination') && !String(url).includes('/http-simulation'))).toBe(true);
  });

  it('shows admin execution controls and clears the session on sign-out', async () => {
    mockBackend('ADMIN');
    render(<App />);
    await signIn('admin');
    expect(await screen.findByText('admin · ADMIN')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Run experiment' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Sign out/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Run experiment' })).not.toBeInTheDocument();
  });
});
