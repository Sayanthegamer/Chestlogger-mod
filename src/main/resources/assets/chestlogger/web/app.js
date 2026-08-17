/**
 * ChestLogger Web Admin Dashboard - Single Page Application Client
 * Pure Vanilla ES6 JavaScript (Zero external dependencies)
 */

(function () {
    'use strict';

    // State
    const state = {
        token: localStorage.getItem('chestlogger_auth_token') || '',
        activeQueryId: null,
        currentPage: 1,
        totalPages: 1,
        totalRecords: 0,
        filters: {
            x: '',
            y: '',
            z: '',
            dim: '',
            player: '',
            item: '',
            sinceSeconds: '0',
            limit: '25'
        },
        statsTimer: null,
        isConnected: false
    };

    // DOM Elements
    const elements = {
        statusPill: document.getElementById('connection-status'),
        statusText: document.getElementById('status-text'),
        authStatusLabel: document.getElementById('auth-status-label'),
        btnOpenAuth: document.getElementById('btn-open-auth'),
        btnRefreshAll: document.getElementById('btn-refresh-all'),
        
        statQueueDepth: document.getElementById('stat-queue-depth'),
        statQueueCap: document.getElementById('stat-queue-cap'),
        queueProgressBar: document.getElementById('queue-progress-bar'),
        statEnqueued: document.getElementById('stat-enqueued'),
        statDrained: document.getElementById('stat-drained'),
        statDropped: document.getElementById('stat-dropped'),
        statIndexSize: document.getElementById('stat-index-size'),
        statUptime: document.getElementById('stat-uptime'),

        filterForm: document.getElementById('filter-form'),
        inputX: document.getElementById('input-x'),
        inputY: document.getElementById('input-y'),
        inputZ: document.getElementById('input-z'),
        selectDim: document.getElementById('select-dim'),
        inputPlayer: document.getElementById('input-player'),
        inputItem: document.getElementById('input-item'),
        selectTimeframe: document.getElementById('select-timeframe'),
        selectLimit: document.getElementById('select-limit'),
        btnSearch: document.getElementById('btn-search'),
        btnClearFilters: document.getElementById('btn-clear-filters'),
        btnExportCsv: document.getElementById('btn-export-csv'),
        btnExportJson: document.getElementById('btn-export-json'),

        resultsSummary: document.getElementById('results-summary'),
        logsTbody: document.getElementById('logs-tbody'),
        currentPage: document.getElementById('current-page'),
        totalPages: document.getElementById('total-pages'),
        btnPageFirst: document.getElementById('btn-page-first'),
        btnPagePrev: document.getElementById('btn-page-prev'),
        btnPageNext: document.getElementById('btn-page-next'),
        btnPageLast: document.getElementById('btn-page-last'),

        authModal: document.getElementById('auth-modal'),
        btnCloseModal: document.getElementById('btn-close-modal'),
        inputSecretToken: document.getElementById('input-secret-token'),
        btnTestAuth: document.getElementById('btn-test-auth'),
        btnSaveAuth: document.getElementById('btn-save-auth'),
        authFeedback: document.getElementById('auth-feedback'),
        toastContainer: document.getElementById('toast-container')
    };

    // Initialize
    function init() {
        bindEvents();
        updateAuthButtonLabel();

        // Check if token in URL query param: ?token=...
        const urlParams = new URLSearchParams(window.location.search);
        if (urlParams.has('token')) {
            const urlToken = urlParams.get('token');
            if (urlToken) {
                state.token = urlToken;
                localStorage.setItem('chestlogger_auth_token', urlToken);
                updateAuthButtonLabel();
            }
        }

        // Fetch initial telemetry
        fetchStats();
        // Start 5-second polling timer
        state.statsTimer = setInterval(fetchStats, 5000);

        // Fetch initial logs
        fetchLogs(1);
    }

    function bindEvents() {
        elements.btnOpenAuth.addEventListener('click', openAuthModal);
        elements.btnCloseModal.addEventListener('click', closeAuthModal);
        elements.authModal.addEventListener('click', (e) => {
            if (e.target === elements.authModal) closeAuthModal();
        });

        elements.btnSaveAuth.addEventListener('click', saveAuthToken);
        elements.btnTestAuth.addEventListener('click', testAuthConnection);
        elements.btnRefreshAll.addEventListener('click', () => {
            fetchStats();
            fetchLogs(state.currentPage);
            showToast('Refreshed telemetry and log data.');
        });

        elements.filterForm.addEventListener('submit', (e) => {
            e.preventDefault();
            readFiltersFromUI();
            state.activeQueryId = null; // reset session for new search
            fetchLogs(1);
        });

        elements.btnClearFilters.addEventListener('click', clearFilters);
        elements.btnExportCsv.addEventListener('click', () => triggerExport('csv'));
        elements.btnExportJson.addEventListener('click', () => triggerExport('json'));

        // Pagination buttons
        elements.btnPageFirst.addEventListener('click', () => fetchLogs(1));
        elements.btnPagePrev.addEventListener('click', () => fetchLogs(state.currentPage - 1));
        elements.btnPageNext.addEventListener('click', () => fetchLogs(state.currentPage + 1));
        elements.btnPageLast.addEventListener('click', () => fetchLogs(state.totalPages));
    }

    function updateAuthButtonLabel() {
        if (state.token) {
            elements.authStatusLabel.textContent = 'Token Set';
            elements.btnOpenAuth.classList.remove('btn-secondary');
            elements.btnOpenAuth.classList.add('btn-outline');
        } else {
            elements.authStatusLabel.textContent = 'Auth Token';
            elements.btnOpenAuth.classList.remove('btn-outline');
            elements.btnOpenAuth.classList.add('btn-secondary');
        }
    }

    function setConnectionStatus(online, message) {
        state.isConnected = online;
        if (online) {
            elements.statusPill.className = 'status-pill online';
            elements.statusText.textContent = 'Connected';
        } else {
            elements.statusPill.className = 'status-pill offline';
            elements.statusText.textContent = message || 'Disconnected';
        }
    }

    function getAuthHeaders() {
        const headers = {};
        if (state.token) {
            headers['X-ChestLogger-Auth'] = state.token;
            headers['Authorization'] = 'Bearer ' + state.token;
        }
        return headers;
    }

    // Telemetry Fetcher
    async function fetchStats() {
        try {
            const resp = await fetch('/api/v1/stats', {
                headers: getAuthHeaders()
            });

            if (resp.status === 401) {
                setConnectionStatus(false, 'Unauthorized');
                return;
            }

            if (!resp.ok) {
                setConnectionStatus(false, 'HTTP ' + resp.status);
                return;
            }

            const data = await resp.json();
            setConnectionStatus(true);
            renderStats(data);
        } catch (err) {
            setConnectionStatus(false, 'Offline');
        }
    }

    function renderStats(data) {
        if (!data || !data.queue) return;

        const q = data.queue;
        elements.statQueueDepth.textContent = q.depth.toLocaleString();
        elements.statQueueCap.textContent = q.capacity.toLocaleString();

        const pct = q.capacity > 0 ? Math.min(100, Math.round((q.depth / q.capacity) * 100)) : 0;
        elements.queueProgressBar.style.width = pct + '%';

        elements.statEnqueued.textContent = q.enqueued.toLocaleString();
        elements.statDrained.textContent = q.drained.toLocaleString();
        elements.statDropped.textContent = q.dropped.toLocaleString();

        if (data.index) {
            elements.statIndexSize.textContent = data.index.size.toLocaleString();
        }

        if (data.uptimeMs != null) {
            elements.statUptime.textContent = formatUptime(data.uptimeMs);
        }
    }

    function formatUptime(ms) {
        const sec = Math.floor(ms / 1000);
        const d = Math.floor(sec / 86400);
        const h = Math.floor((sec % 86400) / 3600);
        const m = Math.floor((sec % 3600) / 60);
        const s = sec % 60;
        if (d > 0) return `${d}d ${h}h`;
        if (h > 0) return `${h}h ${m}m`;
        return `${m}m ${s}s`;
    }

    function readFiltersFromUI() {
        state.filters.x = elements.inputX.value.trim();
        state.filters.y = elements.inputY.value.trim();
        state.filters.z = elements.inputZ.value.trim();
        state.filters.dim = elements.selectDim.value;
        state.filters.player = elements.inputPlayer.value.trim();
        state.filters.item = elements.inputItem.value.trim();
        state.filters.sinceSeconds = elements.selectTimeframe.value;
        state.filters.limit = elements.selectLimit.value;
    }

    function clearFilters() {
        elements.inputX.value = '';
        elements.inputY.value = '';
        elements.inputZ.value = '';
        elements.selectDim.value = '';
        elements.inputPlayer.value = '';
        elements.inputItem.value = '';
        elements.selectTimeframe.value = '0';
        elements.selectLimit.value = '25';

        readFiltersFromUI();
        state.activeQueryId = null;
        fetchLogs(1);
    }

    // Query & Fetch Logs
    async function fetchLogs(targetPage) {
        renderTableLoading();

        const queryParams = new URLSearchParams();
        queryParams.set('page', targetPage || 1);
        queryParams.set('limit', state.filters.limit || 25);

        if (state.activeQueryId) {
            queryParams.set('queryId', state.activeQueryId);
        } else {
            if (state.filters.x !== '') queryParams.set('x', state.filters.x);
            if (state.filters.y !== '') queryParams.set('y', state.filters.y);
            if (state.filters.z !== '') queryParams.set('z', state.filters.z);
            if (state.filters.dim) queryParams.set('dim', state.filters.dim);
            if (state.filters.player) queryParams.set('player', state.filters.player);
            if (state.filters.item) queryParams.set('item', state.filters.item);
            if (state.filters.sinceSeconds && state.filters.sinceSeconds !== '0') {
                queryParams.set('sinceSeconds', state.filters.sinceSeconds);
            }
        }

        try {
            const resp = await fetch('/api/v1/query?' + queryParams.toString(), {
                headers: getAuthHeaders()
            });

            if (resp.status === 401) {
                renderTableEmpty('Authentication required. Click "Auth Token" in the top-right to enter your secretToken.');
                openAuthModal();
                return;
            }

            if (!resp.ok) {
                const errData = await resp.json().catch(() => ({}));
                renderTableEmpty('Error fetching logs: ' + (errData.error || ('HTTP ' + resp.status)));
                return;
            }

            const data = await resp.json();
            state.activeQueryId = data.queryId || null;
            state.currentPage = data.page || 1;
            state.totalPages = Math.max(1, data.totalPages || 1);
            state.totalRecords = data.totalRecords || 0;

            renderTableData(data.records || []);
            updatePaginationUI();
        } catch (err) {
            renderTableEmpty('Failed to connect to server: ' + err.message);
        }
    }

    function renderTableLoading() {
        elements.logsTbody.innerHTML = `
            <tr>
                <td colspan="8" class="table-placeholder">
                    <div class="empty-state">
                        <span class="empty-icon">⏳</span>
                        <p>Querying server index and loading records...</p>
                    </div>
                </td>
            </tr>
        `;
    }

    function renderTableEmpty(message) {
        elements.logsTbody.innerHTML = `
            <tr>
                <td colspan="8" class="table-placeholder">
                    <div class="empty-state">
                        <span class="empty-icon">📂</span>
                        <p>${escapeHtml(message || 'No transaction records found matching the current filters.')}</p>
                    </div>
                </td>
            </tr>
        `;
        elements.resultsSummary.textContent = 'Showing 0 records';
        state.totalPages = 1;
        state.currentPage = 1;
        updatePaginationUI();
    }

    function renderTableData(records) {
        if (!records || records.length === 0) {
            renderTableEmpty();
            return;
        }

        elements.resultsSummary.textContent = `Showing ${records.length} of ${state.totalRecords.toLocaleString()} records`;

        let html = '';
        for (const rec of records) {
            const timeStr = formatTimestamp(rec.timestamp || rec.timestampMs);
            const posX = (rec.x !== undefined && rec.x !== null) ? rec.x : '-';
            const posY = (rec.y !== undefined && rec.y !== null) ? rec.y : '-';
            const posZ = (rec.z !== undefined && rec.z !== null) ? rec.z : '-';
            const posStr = (posX !== '-' && posY !== '-' && posZ !== '-') ? `(${posX}, ${posY}, ${posZ})` : '-';
            const rawDim = rec.dimension || 'minecraft:overworld';
            const dimShort = rawDim.replace(/^minecraft:/, '');
            const rawItemId = rec.itemId || rec.item || '';
            const itemName = formatItemName(rawItemId);
            const deltaClass = rec.delta >= 0 ? 'delta-pos' : 'delta-neg';
            const deltaSign = rec.delta > 0 ? '+' : '';

            html += `
                <tr>
                    <td>${escapeHtml(timeStr)}</td>
                    <td class="coord-cell">${escapeHtml(posStr)}</td>
                    <td><span class="badge-action">${escapeHtml(dimShort)}</span></td>
                    <td><span class="badge-actor">${escapeHtml(rec.actorName || 'Unknown')}</span></td>
                    <td><span class="badge-action">${escapeHtml(rec.action || 'INTERACT')}</span></td>
                    <td class="coord-cell">#${rec.slot < 10 ? '0' + rec.slot : rec.slot}</td>
                    <td><strong>${escapeHtml(itemName)}</strong></td>
                    <td class="text-right">
                        <span class="delta-pill ${deltaClass}">${deltaSign}${rec.delta}</span>
                    </td>
                </tr>
            `;
        }

        elements.logsTbody.innerHTML = html;
    }

    function updatePaginationUI() {
        elements.currentPage.textContent = state.currentPage;
        elements.totalPages.textContent = state.totalPages;

        elements.btnPageFirst.disabled = state.currentPage <= 1;
        elements.btnPagePrev.disabled = state.currentPage <= 1;
        elements.btnPageNext.disabled = state.currentPage >= state.totalPages;
        elements.btnPageLast.disabled = state.currentPage >= state.totalPages;
    }

    // Export Trigger
    function triggerExport(format) {
        readFiltersFromUI();
        const exportParams = new URLSearchParams();
        exportParams.set('format', format);

        if (state.filters.x !== '') exportParams.set('x', state.filters.x);
        if (state.filters.y !== '') exportParams.set('y', state.filters.y);
        if (state.filters.z !== '') exportParams.set('z', state.filters.z);
        if (state.filters.dim) exportParams.set('dim', state.filters.dim);
        if (state.filters.player) exportParams.set('player', state.filters.player);
        if (state.filters.item) exportParams.set('item', state.filters.item);
        if (state.filters.sinceSeconds && state.filters.sinceSeconds !== '0') {
            exportParams.set('sinceSeconds', state.filters.sinceSeconds);
        }
        if (state.token) {
            exportParams.set('token', state.token);
        }

        const exportUrl = '/api/v1/export?' + exportParams.toString();
        showToast(`Preparing ${format.toUpperCase()} export download...`);

        // Trigger direct browser download
        const a = document.createElement('a');
        a.href = exportUrl;
        a.download = '';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    // Auth Modal Handlers
    function openAuthModal() {
        elements.inputSecretToken.value = state.token;
        elements.authFeedback.className = 'auth-feedback hidden';
        elements.authFeedback.textContent = '';
        elements.authModal.classList.remove('hidden');
    }

    function closeAuthModal() {
        elements.authModal.classList.add('hidden');
    }

    async function testAuthConnection() {
        const testToken = elements.inputSecretToken.value.trim();
        elements.authFeedback.className = 'auth-feedback';
        elements.authFeedback.textContent = 'Testing connection...';

        try {
            const resp = await fetch('/api/v1/health', {
                headers: {
                    'X-ChestLogger-Auth': testToken
                }
            });

            if (resp.ok) {
                elements.authFeedback.className = 'auth-feedback success';
                elements.authFeedback.textContent = '✓ Successfully authenticated with server!';
            } else if (resp.status === 401) {
                elements.authFeedback.className = 'auth-feedback error';
                elements.authFeedback.textContent = '✗ Invalid secretToken. Please check config/chestlogger_web.json';
            } else if (resp.status === 429) {
                elements.authFeedback.className = 'auth-feedback error';
                elements.authFeedback.textContent = '✗ Too many failed attempts. Rate limited for 60s.';
            } else {
                elements.authFeedback.className = 'auth-feedback error';
                elements.authFeedback.textContent = '✗ Server returned HTTP ' + resp.status;
            }
        } catch (err) {
            elements.authFeedback.className = 'auth-feedback error';
            elements.authFeedback.textContent = '✗ Could not connect: ' + err.message;
        }
    }

    function saveAuthToken() {
        const newToken = elements.inputSecretToken.value.trim();
        state.token = newToken;
        if (newToken) {
            localStorage.setItem('chestlogger_auth_token', newToken);
        } else {
            localStorage.removeItem('chestlogger_auth_token');
        }

        updateAuthButtonLabel();
        closeAuthModal();
        showToast(newToken ? 'Auth token saved.' : 'Auth token cleared.');
        
        fetchStats();
        fetchLogs(1);
    }

    // Utilities
    function formatTimestamp(ts) {
        if (!ts) return 'Unknown';
        const d = new Date(ts);
        return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) +
            ' ' + d.toLocaleDateString([], { month: 'short', day: 'numeric' });
    }

    function formatItemName(id) {
        if (!id) return 'Unknown Item';
        const raw = id.replace(/^minecraft:/, '');
        return raw.split('_')
            .map(w => w.charAt(0).toUpperCase() + w.slice(1))
            .join(' ');
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    function showToast(msg) {
        const toast = document.createElement('div');
        toast.className = 'toast';
        toast.textContent = msg;
        elements.toastContainer.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 3000);
    }

    // Start App
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
