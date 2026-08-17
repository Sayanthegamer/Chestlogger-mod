/**
 * ChestLogger Web Admin Dashboard - Single Page Application Client
 * Pure Vanilla ES6 JavaScript (Zero external dependencies)
 * Phase 2: Interactive Log Stream, Row Inspector, and Quick-Filter Chips
 */

(function () {
    'use strict';

    // Application State
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
            limit: '25',
            action: ''
        },
        statsTimer: null,
        autoTailInterval: 5000,
        isConnected: false,
        rawRecords: []
    };

    // DOM Elements Mapping
    const elements = {
        // Navigation & Status
        statusPill: document.getElementById('connection-status'),
        statusText: document.getElementById('status-text'),
        authStatusLabel: document.getElementById('auth-status-label'),
        btnOpenAuth: document.getElementById('btn-open-auth'),
        btnRefreshAll: document.getElementById('btn-refresh-all'),

        // Telemetry Strip
        statQueueDepth: document.getElementById('stat-queue-depth'),
        statQueueCap: document.getElementById('stat-queue-cap'),
        queueProgressBar: document.getElementById('queue-progress-bar'),
        statEnqueued: document.getElementById('stat-enqueued'),
        statDrained: document.getElementById('stat-drained'),
        statDropped: document.getElementById('stat-dropped'),
        statIndexSize: document.getElementById('stat-index-size'),
        statUptime: document.getElementById('stat-uptime'),
        selectAutoTail: document.getElementById('select-auto-tail'),

        // Filter Form Controls
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

        // Table & Pagination
        resultsSummary: document.getElementById('results-summary'),
        logsTbody: document.getElementById('logs-tbody'),
        rowDetailTemplate: document.getElementById('row-detail-template'),
        currentPage: document.getElementById('current-page'),
        totalPages: document.getElementById('total-pages'),
        btnPageFirst: document.getElementById('btn-page-first'),
        btnPagePrev: document.getElementById('btn-page-prev'),
        btnPageNext: document.getElementById('btn-page-next'),
        btnPageLast: document.getElementById('btn-page-last'),

        // Auth Modal
        authModal: document.getElementById('auth-modal'),
        btnCloseModal: document.getElementById('btn-close-modal'),
        inputSecretToken: document.getElementById('input-secret-token'),
        btnTestAuth: document.getElementById('btn-test-auth'),
        btnSaveAuth: document.getElementById('btn-save-auth'),
        authFeedback: document.getElementById('auth-feedback'),
        toastContainer: document.getElementById('toast-container')
    };

    // Initialize Application
    function init() {
        bindEvents();
        updateAuthButtonLabel();
        syncChipsUI();

        // Check if token was provided in URL query string: ?token=...
        const urlParams = new URLSearchParams(window.location.search);
        if (urlParams.has('token')) {
            const urlToken = urlParams.get('token');
            if (urlToken) {
                state.token = urlToken;
                localStorage.setItem('chestlogger_auth_token', urlToken);
                updateAuthButtonLabel();
            }
        }

        // Fetch initial telemetry stats
        fetchStats();
        // Start auto-tail / polling timer based on default selection
        setupAutoTailTimer();

        // Fetch initial log records
        fetchLogs(1);
    }

    // Event Bindings
    function bindEvents() {
        // Auth Modal
        if (elements.btnOpenAuth) elements.btnOpenAuth.addEventListener('click', openAuthModal);
        if (elements.btnCloseModal) elements.btnCloseModal.addEventListener('click', closeAuthModal);
        if (elements.authModal) {
            elements.authModal.addEventListener('click', (e) => {
                if (e.target === elements.authModal) closeAuthModal();
            });
        }
        if (elements.btnSaveAuth) elements.btnSaveAuth.addEventListener('click', saveAuthToken);
        if (elements.btnTestAuth) elements.btnTestAuth.addEventListener('click', testAuthConnection);

        // Header Actions
        if (elements.btnRefreshAll) {
            elements.btnRefreshAll.addEventListener('click', () => {
                fetchStats();
                fetchLogs(state.currentPage);
                showToast('Refreshed telemetry and transaction records.');
            });
        }

        // Live Auto-Tail Select
        if (elements.selectAutoTail) {
            elements.selectAutoTail.addEventListener('change', () => {
                const val = parseInt(elements.selectAutoTail.value, 10) || 0;
                state.autoTailInterval = val;
                setupAutoTailTimer();
            });
        }

        // Search & Filter Form
        if (elements.filterForm) {
            elements.filterForm.addEventListener('submit', (e) => {
                e.preventDefault();
                readFiltersFromUI();
                state.activeQueryId = null; // reset session for new search
                fetchLogs(1);
            });
        }

        if (elements.btnClearFilters) elements.btnClearFilters.addEventListener('click', clearFilters);
        if (elements.btnExportCsv) elements.btnExportCsv.addEventListener('click', () => triggerExport('csv'));
        if (elements.btnExportJson) elements.btnExportJson.addEventListener('click', () => triggerExport('json'));

        // Form Select Changes Sync Chips
        if (elements.selectDim) {
            elements.selectDim.addEventListener('change', () => {
                syncChipsUI();
            });
        }
        if (elements.selectTimeframe) {
            elements.selectTimeframe.addEventListener('change', () => {
                syncChipsUI();
            });
        }

        // Quick-Filter Query Chips
        bindQuickFilterChips();

        // Pagination Controls
        if (elements.btnPageFirst) elements.btnPageFirst.addEventListener('click', () => fetchLogs(1));
        if (elements.btnPagePrev) elements.btnPagePrev.addEventListener('click', () => fetchLogs(state.currentPage - 1));
        if (elements.btnPageNext) elements.btnPageNext.addEventListener('click', () => fetchLogs(state.currentPage + 1));
        if (elements.btnPageLast) elements.btnPageLast.addEventListener('click', () => fetchLogs(state.totalPages));
    }

    // Quick-Filter Chips Handler
    function bindQuickFilterChips() {
        const filterChips = document.querySelectorAll('.filter-chip');
        filterChips.forEach(chip => {
            chip.addEventListener('click', () => {
                const filterType = chip.dataset.filter;
                const filterVal = chip.dataset.val;

                if (filterType === 'dim') {
                    if (elements.selectDim.value === filterVal) {
                        elements.selectDim.value = '';
                    } else {
                        elements.selectDim.value = filterVal;
                    }
                    syncChipsUI();
                    readFiltersFromUI();
                    state.activeQueryId = null;
                    fetchLogs(1);
                } else if (filterType === 'action') {
                    if (state.filters.action === filterVal) {
                        state.filters.action = '';
                    } else {
                        state.filters.action = filterVal;
                    }
                    syncChipsUI();
                    state.activeQueryId = null;
                    fetchLogs(1);
                } else if (filterType === 'time') {
                    if (elements.selectTimeframe.value === filterVal) {
                        elements.selectTimeframe.value = '0';
                    } else {
                        elements.selectTimeframe.value = filterVal;
                    }
                    syncChipsUI();
                    readFiltersFromUI();
                    state.activeQueryId = null;
                    fetchLogs(1);
                }
            });
        });
    }

    // Synchronize Quick Chips Active States
    function syncChipsUI() {
        const dimVal = elements.selectDim ? elements.selectDim.value : '';
        const timeVal = elements.selectTimeframe ? elements.selectTimeframe.value : '0';
        const actionVal = state.filters.action || '';

        document.querySelectorAll('.filter-chip[data-filter="dim"]').forEach(chip => {
            chip.classList.toggle('active', chip.dataset.val === dimVal);
        });

        document.querySelectorAll('.filter-chip[data-filter="time"]').forEach(chip => {
            chip.classList.toggle('active', chip.dataset.val === timeVal);
        });

        document.querySelectorAll('.filter-chip[data-filter="action"]').forEach(chip => {
            chip.classList.toggle('active', chip.dataset.val === actionVal);
        });
    }

    // Live Stream Auto-Tail Timer Setup
    function setupAutoTailTimer() {
        if (state.statsTimer) {
            clearInterval(state.statsTimer);
            state.statsTimer = null;
        }

        const liveIndicator = document.querySelector('.live-indicator');
        if (state.autoTailInterval > 0) {
            if (liveIndicator) liveIndicator.style.opacity = '1';
            state.statsTimer = setInterval(() => {
                fetchStats();
                // If currently on first page and modal is closed, refresh latest logs
                if (state.currentPage === 1 && (!elements.authModal || elements.authModal.classList.contains('hidden'))) {
                    // Refresh current live records
                    fetchLogsSilently(1);
                }
            }, state.autoTailInterval);
        } else {
            if (liveIndicator) liveIndicator.style.opacity = '0.35';
        }
    }

    // Auth Helpers
    function updateAuthButtonLabel() {
        if (!elements.authStatusLabel || !elements.btnOpenAuth) return;
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
        if (!elements.statusPill || !elements.statusText) return;
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
        if (elements.statQueueDepth) elements.statQueueDepth.textContent = (q.depth || 0).toLocaleString();
        if (elements.statQueueCap) elements.statQueueCap.textContent = (q.capacity || 0).toLocaleString();

        const pct = (q.capacity > 0) ? Math.min(100, Math.round(((q.depth || 0) / q.capacity) * 100)) : 0;
        if (elements.queueProgressBar) elements.queueProgressBar.style.width = pct + '%';

        if (elements.statEnqueued) elements.statEnqueued.textContent = (q.enqueued || 0).toLocaleString();
        if (elements.statDrained) elements.statDrained.textContent = (q.drained || 0).toLocaleString();
        if (elements.statDropped) elements.statDropped.textContent = (q.dropped || 0).toLocaleString();

        if (elements.statIndexSize && data.index) {
            elements.statIndexSize.textContent = (data.index.size || 0).toLocaleString();
        }

        if (elements.statUptime && data.uptimeMs != null) {
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

    // Filter Form Reading & Reset
    function readFiltersFromUI() {
        state.filters.x = elements.inputX ? elements.inputX.value.trim() : '';
        state.filters.y = elements.inputY ? elements.inputY.value.trim() : '';
        state.filters.z = elements.inputZ ? elements.inputZ.value.trim() : '';
        state.filters.dim = elements.selectDim ? elements.selectDim.value : '';
        state.filters.player = elements.inputPlayer ? elements.inputPlayer.value.trim() : '';
        state.filters.item = elements.inputItem ? elements.inputItem.value.trim() : '';
        state.filters.sinceSeconds = elements.selectTimeframe ? elements.selectTimeframe.value : '0';
        state.filters.limit = elements.selectLimit ? elements.selectLimit.value : '25';
    }

    function clearFilters() {
        if (elements.inputX) elements.inputX.value = '';
        if (elements.inputY) elements.inputY.value = '';
        if (elements.inputZ) elements.inputZ.value = '';
        if (elements.selectDim) elements.selectDim.value = '';
        if (elements.inputPlayer) elements.inputPlayer.value = '';
        if (elements.inputItem) elements.inputItem.value = '';
        if (elements.selectTimeframe) elements.selectTimeframe.value = '0';
        if (elements.selectLimit) elements.selectLimit.value = '25';
        state.filters.action = '';

        syncChipsUI();
        readFiltersFromUI();
        state.activeQueryId = null;
        fetchLogs(1);
    }

    // Query & Fetch Logs
    async function fetchLogs(targetPage) {
        renderTableLoading();
        await executeFetchLogs(targetPage);
    }

    async function fetchLogsSilently(targetPage) {
        await executeFetchLogs(targetPage);
    }

    async function executeFetchLogs(targetPage) {
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
            state.rawRecords = data.records || [];

            renderTableData(state.rawRecords);
            updatePaginationUI();
        } catch (err) {
            renderTableEmpty('Failed to connect to server: ' + err.message);
        }
    }

    function renderTableLoading() {
        if (!elements.logsTbody) return;
        elements.logsTbody.innerHTML = `
            <tr>
                <td colspan="10" class="table-placeholder">
                    <div class="empty-state">
                        <svg class="empty-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                            <circle cx="12" cy="12" r="10"/>
                            <polyline points="12 6 12 12 16 14"/>
                        </svg>
                        <p class="empty-text-main">Loading transaction audit records...</p>
                        <p class="empty-text-sub">Querying server ring buffer and spatial indexes.</p>
                    </div>
                </td>
            </tr>
        `;
    }

    function renderTableEmpty(message) {
        if (!elements.logsTbody) return;
        elements.logsTbody.innerHTML = `
            <tr>
                <td colspan="10" class="table-placeholder">
                    <div class="empty-state">
                        <svg class="empty-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/>
                            <path d="m3.3 7 8.7 5 8.7-5"/>
                            <path d="M12 22V12"/>
                        </svg>
                        <p class="empty-text-main">${escapeHtml(message || 'No transaction records found matching the current filters.')}</p>
                        <p class="empty-text-sub">Apply query filters or click "Search Logs" to inspect container transactions.</p>
                    </div>
                </td>
            </tr>
        `;
        if (elements.resultsSummary) elements.resultsSummary.textContent = 'Showing 0 records';
        state.totalPages = 1;
        state.currentPage = 1;
        updatePaginationUI();
    }

    // Render Table Data & Rows
    function renderTableData(records) {
        // If action filter is active (TAKE or PUT), filter records client-side
        let displayRecords = records || [];
        if (state.filters.action) {
            displayRecords = displayRecords.filter(rec => (rec.action || '').toUpperCase() === state.filters.action);
        }

        if (!displayRecords || displayRecords.length === 0) {
            renderTableEmpty();
            return;
        }

        if (elements.resultsSummary) {
            elements.resultsSummary.textContent = `Showing ${displayRecords.length} of ${state.totalRecords.toLocaleString()} records`;
        }

        elements.logsTbody.innerHTML = '';

        displayRecords.forEach((rec, idx) => {
            const timeObj = rec.timestamp || rec.timestampMs;
            const timeStr = formatTimestamp(timeObj);
            const isoTime = timeObj ? new Date(timeObj).toISOString() : 'Unknown';

            const posX = (rec.x !== undefined && rec.x !== null) ? rec.x : '-';
            const posY = (rec.y !== undefined && rec.y !== null) ? rec.y : '-';
            const posZ = (rec.z !== undefined && rec.z !== null) ? rec.z : '-';
            const posStr = (posX !== '-' && posY !== '-' && posZ !== '-') ? `(${posX}, ${posY}, ${posZ})` : '-';

            const rawDim = rec.dimension || 'minecraft:overworld';
            const dimShort = rawDim.replace(/^minecraft:/, '');
            let dimClass = 'dim-overworld';
            if (rawDim.includes('nether')) dimClass = 'dim-nether';
            else if (rawDim.includes('end')) dimClass = 'dim-end';

            const rawActor = rec.actorName || rec.actorUuid || 'Unknown';

            const act = (rec.action || 'INTERACT').toUpperCase();
            let actClass = 'action-interact';
            if (act === 'TAKE') actClass = 'action-take';
            else if (act === 'PUT') actClass = 'action-put';
            else if (act === 'CLEAR') actClass = 'action-clear';

            const slotNum = rec.slot != null ? (rec.slot < 10 ? '0' + rec.slot : rec.slot) : '--';

            const rawItemId = rec.itemId || rec.item || '';
            const itemName = formatItemName(rawItemId);

            const deltaVal = rec.delta != null ? rec.delta : 0;
            const deltaClass = deltaVal >= 0 ? 'delta-pos' : 'delta-neg';
            const deltaSign = deltaVal > 0 ? '+' : '';

            const tr = document.createElement('tr');
            tr.className = 'log-row';
            tr.dataset.index = String(idx);

            tr.innerHTML = `
                <td class="col-expand">
                    <button type="button" class="btn-row-expand btn-inspect-mini" title="Toggle transaction details" aria-label="Toggle details">
                        <svg class="svg-icon-xs chevron-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <polyline points="9 18 15 12 9 6"/>
                        </svg>
                    </button>
                </td>
                <td>
                    <span class="text-muted-mono" title="${escapeHtml(isoTime)}">${escapeHtml(timeStr)}</span>
                </td>
                <td>
                    <span class="badge-coord coord-cell" title="Click to filter by coordinates" style="cursor: pointer;">${escapeHtml(posStr)}</span>
                </td>
                <td>
                    <span class="badge-dim ${dimClass}">${escapeHtml(dimShort)}</span>
                </td>
                <td>
                    <span class="badge-actor" title="${escapeHtml(rec.actorUuid || '')}">${escapeHtml(rawActor)}</span>
                </td>
                <td>
                    <span class="badge-action ${actClass}">${escapeHtml(act)}</span>
                </td>
                <td>
                    <span class="badge-slot slot-pill">#${escapeHtml(slotNum)}</span>
                </td>
                <td>
                    <span class="font-bold" title="${escapeHtml(rawItemId)}">${escapeHtml(itemName)}</span>
                </td>
                <td class="text-right">
                    <span class="delta-pill ${deltaClass}">${deltaSign}${deltaVal}</span>
                </td>
                <td class="col-helpers text-center">
                    <button type="button" class="btn-inspect-mini btn-quick-inspect" title="Inspect transaction details" aria-label="Inspect details">
                        <svg class="svg-icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <circle cx="11" cy="11" r="8"/>
                            <line x1="21" y1="21" x2="16.65" y2="16.65"/>
                        </svg>
                    </button>
                </td>
            `;

            // Expansion & Inspection Handlers
            const btnExpand = tr.querySelector('.btn-row-expand');
            const btnInspect = tr.querySelector('.btn-quick-inspect');
            const coordBadge = tr.querySelector('.badge-coord');

            function toggleRowDetail() {
                const nextElem = tr.nextElementSibling;
                const isCurrentlyExpanded = nextElem && nextElem.classList.contains('row-detail-expanded');

                if (isCurrentlyExpanded) {
                    // Collapse
                    tr.classList.remove('is-expanded', 'expanded');
                    const chevron = tr.querySelector('.chevron-icon');
                    if (chevron) chevron.classList.remove('expanded');
                    nextElem.remove();
                } else {
                    // Expand
                    tr.classList.add('is-expanded', 'expanded');
                    const chevron = tr.querySelector('.chevron-icon');
                    if (chevron) chevron.classList.add('expanded');

                    const detailRow = createDetailRow(rec);
                    tr.insertAdjacentElement('afterend', detailRow);
                }
            }

            tr.addEventListener('click', (e) => {
                // Ignore click if user clicked on coord badge or a button inside the row
                if (e.target.closest('.badge-coord') || e.target.closest('button')) return;
                toggleRowDetail();
            });

            if (btnExpand) {
                btnExpand.addEventListener('click', (e) => {
                    e.stopPropagation();
                    toggleRowDetail();
                });
            }

            if (btnInspect) {
                btnInspect.addEventListener('click', (e) => {
                    e.stopPropagation();
                    toggleRowDetail();
                });
            }

            if (coordBadge && posX !== '-') {
                coordBadge.addEventListener('click', (e) => {
                    e.stopPropagation();
                    if (elements.inputX) elements.inputX.value = posX;
                    if (elements.inputY) elements.inputY.value = posY;
                    if (elements.inputZ) elements.inputZ.value = posZ;
                    if (elements.selectDim) elements.selectDim.value = rec.dimension || '';
                    syncChipsUI();
                    readFiltersFromUI();
                    state.activeQueryId = null;
                    fetchLogs(1);
                    showToast(`Filtered by coordinates: (${posX}, ${posY}, ${posZ})`);
                });
            }

            elements.logsTbody.appendChild(tr);
        });
    }

    // Detail Row Constructor from Template
    function createDetailRow(rec) {
        const template = elements.rowDetailTemplate;
        let detailTr;
        if (template && template.content) {
            const clone = template.content.cloneNode(true);
            detailTr = clone.querySelector('tr');
        } else {
            detailTr = document.createElement('tr');
            detailTr.className = 'row-detail-expanded';
            detailTr.innerHTML = '<td colspan="10"><div class="detail-container"></div></td>';
        }

        const txUuid = rec.transactionId || rec.uuid || rec.id || 'N/A';
        const seqNum = '#' + (rec.sequenceNumber != null ? rec.sequenceNumber : (rec.sequenceId != null ? rec.sequenceId : (rec.seq != null ? rec.seq : '-')));
        const posX = (rec.x !== undefined && rec.x !== null) ? rec.x : '-';
        const posY = (rec.y !== undefined && rec.y !== null) ? rec.y : '-';
        const posZ = (rec.z !== undefined && rec.z !== null) ? rec.z : '-';
        const containerPos = `X: ${posX}, Y: ${posY}, Z: ${posZ}`;
        const dimension = rec.dimension || 'minecraft:overworld';
        const actorUuid = rec.actorUuid || rec.playerUuid || 'N/A';
        const slotIndex = '#' + (rec.slot != null ? (rec.slot < 10 ? '0' + rec.slot : rec.slot) : '-');
        const prevItem = rec.prevItem || (rec.delta < 0 ? (rec.itemId || rec.item || 'empty') : 'empty');
        const currItem = rec.itemId || rec.item || 'empty';

        const deltaVal = rec.delta != null ? rec.delta : 0;
        const deltaClass = deltaVal >= 0 ? 'delta-pos' : 'delta-neg';
        const deltaSign = deltaVal > 0 ? '+' : '';

        // Populate detail fields
        const setFieldText = (fieldName, text) => {
            const el = detailTr.querySelector(`[data-field="${fieldName}"]`);
            if (el) el.textContent = text;
        };

        setFieldText('tx-uuid', txUuid);
        setFieldText('seq-num', seqNum);
        setFieldText('container-pos', containerPos);
        setFieldText('dimension', dimension);
        setFieldText('actor-uuid', actorUuid);
        setFieldText('slot-index', slotIndex);
        setFieldText('prev-item', prevItem);
        setFieldText('curr-item', currItem);

        const deltaPillEl = detailTr.querySelector('[data-field="delta-pill"]');
        if (deltaPillEl) {
            deltaPillEl.innerHTML = `<span class="delta-pill ${deltaClass}">${deltaSign}${deltaVal}</span>`;
        }

        // Bind Detail Row Action Buttons
        const btnCopyCmd = detailTr.querySelector('.btn-copy-cmd');
        if (btnCopyCmd) {
            btnCopyCmd.addEventListener('click', (e) => {
                e.stopPropagation();
                const cmdX = (rec.x !== undefined && rec.x !== null) ? rec.x : 0;
                const cmdY = (rec.y !== undefined && rec.y !== null) ? rec.y : 0;
                const cmdZ = (rec.z !== undefined && rec.z !== null) ? rec.z : 0;
                const cmdDim = rec.dimension || 'minecraft:overworld';
                const cmd = `/chestlog rollback ${cmdX} ${cmdY} ${cmdZ} ${cmdDim} 1h`;
                copyToClipboard(cmd, `Copied rollback command: ${cmd}`);
            });
        }

        const btnCopyJson = detailTr.querySelector('.btn-copy-json');
        if (btnCopyJson) {
            btnCopyJson.addEventListener('click', (e) => {
                e.stopPropagation();
                const json = JSON.stringify(rec, null, 2);
                copyToClipboard(json, 'Copied raw transaction JSON');
            });
        }

        const btnFilterActor = detailTr.querySelector('.btn-filter-actor');
        if (btnFilterActor) {
            btnFilterActor.addEventListener('click', (e) => {
                e.stopPropagation();
                if (elements.inputPlayer) elements.inputPlayer.value = rec.actorName || rec.actorUuid || '';
                readFiltersFromUI();
                state.activeQueryId = null;
                fetchLogs(1);
                showToast(`Filtering by actor: ${elements.inputPlayer ? elements.inputPlayer.value : ''}`);
            });
        }

        const btnFilterPos = detailTr.querySelector('.btn-filter-pos');
        if (btnFilterPos) {
            btnFilterPos.addEventListener('click', (e) => {
                e.stopPropagation();
                if (elements.inputX) elements.inputX.value = (rec.x !== undefined && rec.x !== null) ? rec.x : '';
                if (elements.inputY) elements.inputY.value = (rec.y !== undefined && rec.y !== null) ? rec.y : '';
                if (elements.inputZ) elements.inputZ.value = (rec.z !== undefined && rec.z !== null) ? rec.z : '';
                if (elements.selectDim) elements.selectDim.value = rec.dimension || '';
                syncChipsUI();
                readFiltersFromUI();
                state.activeQueryId = null;
                fetchLogs(1);
                showToast(`Filtering by container: (${elements.inputX ? elements.inputX.value : ''}, ${elements.inputY ? elements.inputY.value : ''}, ${elements.inputZ ? elements.inputZ.value : ''})`);
            });
        }

        const btnFilterItem = detailTr.querySelector('.btn-filter-item');
        if (btnFilterItem) {
            btnFilterItem.addEventListener('click', (e) => {
                e.stopPropagation();
                if (elements.inputItem) elements.inputItem.value = rec.itemId || rec.item || '';
                readFiltersFromUI();
                state.activeQueryId = null;
                fetchLogs(1);
                showToast(`Filtering by item: ${elements.inputItem ? elements.inputItem.value : ''}`);
            });
        }

        return detailTr;
    }

    // Pagination Controls
    function updatePaginationUI() {
        if (elements.currentPage) elements.currentPage.textContent = state.currentPage;
        if (elements.totalPages) elements.totalPages.textContent = state.totalPages;

        if (elements.btnPageFirst) elements.btnPageFirst.disabled = state.currentPage <= 1;
        if (elements.btnPagePrev) elements.btnPagePrev.disabled = state.currentPage <= 1;
        if (elements.btnPageNext) elements.btnPageNext.disabled = state.currentPage >= state.totalPages;
        if (elements.btnPageLast) elements.btnPageLast.disabled = state.currentPage >= state.totalPages;
    }

    // Export Trigger (CSV & JSON)
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
        if (!elements.authModal) return;
        if (elements.inputSecretToken) elements.inputSecretToken.value = state.token;
        if (elements.authFeedback) {
            elements.authFeedback.className = 'auth-feedback hidden';
            elements.authFeedback.textContent = '';
        }
        elements.authModal.classList.remove('hidden');
    }

    function closeAuthModal() {
        if (!elements.authModal) return;
        elements.authModal.classList.add('hidden');
    }

    async function testAuthConnection() {
        if (!elements.inputSecretToken || !elements.authFeedback) return;
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
        if (!elements.inputSecretToken) return;
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
    function copyToClipboard(text, successToast) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(() => {
                showToast(successToast);
            }).catch(() => {
                fallbackCopy(text, successToast);
            });
        } else {
            fallbackCopy(text, successToast);
        }
    }

    function fallbackCopy(text, successToast) {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try {
            document.execCommand('copy');
            showToast(successToast);
        } catch (e) {
            showToast('Failed to copy to clipboard');
        }
        document.body.removeChild(textarea);
    }

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
        if (!elements.toastContainer) return;
        const toast = document.createElement('div');
        toast.className = 'toast';
        toast.textContent = msg;
        elements.toastContainer.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 3200);
    }

    // Start App on DOM Ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
