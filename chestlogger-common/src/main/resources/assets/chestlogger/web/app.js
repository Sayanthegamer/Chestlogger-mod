/**
 * ChestLogger Web Admin Dashboard - Single Page Application Client
 * Pure Vanilla ES6 JavaScript (Zero external dependencies)
 * Phase 3: Interactive Log Stream, Row Inspector, Quick-Filter Chips,
 * and Item Provenance Journey Graph Visualizer with Step Inspector
 */

(function () {
    'use strict';

    // Application State
    const state = {
        token: localStorage.getItem('chestlogger_auth_token') || '',
        activeTab: 'logs-view',
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
        provenanceGraph: null,
        selectedStepIndex: null,
        statsTimer: null,
        rateLimitTimer: null,
        isRateLimited: false,
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

        // View Tabs
        tabBtnLogs: document.getElementById('tab-btn-logs'),
        tabBtnJourney: document.getElementById('tab-btn-journey'),
        logsView: document.getElementById('logs-view'),
        journeyView: document.getElementById('journey-view'),

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
        liveIndicator: document.querySelector('.live-indicator'),

        // Filter Form Controls (Audit View)
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

        // Table & Pagination (Audit View)
        resultsSummary: document.getElementById('results-summary'),
        logsTbody: document.getElementById('logs-tbody'),
        rowDetailTemplate: document.getElementById('row-detail-template'),
        currentPage: document.getElementById('current-page'),
        totalPages: document.getElementById('total-pages'),
        btnPageFirst: document.getElementById('btn-page-first'),
        btnPagePrev: document.getElementById('btn-page-prev'),
        btnPageNext: document.getElementById('btn-page-next'),
        btnPageLast: document.getElementById('btn-page-last'),

        // Journey / Provenance Elements
        journeyForm: document.getElementById('journey-form'),
        journeyInputItem: document.getElementById('journey-input-item'),
        journeyInputX: document.getElementById('journey-input-x'),
        journeyInputY: document.getElementById('journey-input-y'),
        journeyInputZ: document.getElementById('journey-input-z'),
        journeySelectDim: document.getElementById('journey-select-dim'),
        journeyInputFp: document.getElementById('journey-input-fp'),
        journeySelectHops: document.getElementById('journey-select-hops'),
        btnJourneyReset: document.getElementById('btn-journey-reset'),
        btnJourneyTrace: document.getElementById('btn-journey-trace'),
        btnExportGraphJson: document.getElementById('btn-export-graph-json'),

        // Journey Header & Canvas
        journeyTargetId: document.getElementById('journey-target-id'),
        journeyTotalSteps: document.getElementById('journey-total-steps'),
        journeyOverallConfidence: document.getElementById('journey-overall-confidence'),
        journeyCanvasWrapper: document.getElementById('journey-canvas-wrapper'),
        journeyEmptyState: document.getElementById('journey-empty-state'),
        journeySvgContainer: document.getElementById('journey-svg-container'),
        journeySvg: document.getElementById('journey-svg'),

        // Step Inspector Elements
        stepInspectorCard: document.getElementById('step-inspector-card'),
        inspectorStepTitle: document.getElementById('inspector-step-title'),
        inspectorConfidenceBadge: document.getElementById('inspector-confidence-badge'),
        inspectorPlaceholder: document.getElementById('inspector-placeholder'),
        inspectorDetails: document.getElementById('inspector-details'),
        inspStepSeq: document.getElementById('insp-step-seq'),
        inspTimestamp: document.getElementById('insp-timestamp'),
        inspActionActor: document.getElementById('insp-action-actor'),
        inspActorUuid: document.getElementById('insp-actor-uuid'),
        inspContainerCoord: document.getElementById('insp-container-coord'),
        inspDimension: document.getElementById('insp-dimension'),
        inspDelta: document.getElementById('insp-delta'),
        inspFingerprint: document.getElementById('insp-fingerprint'),
        inspNotes: document.getElementById('insp-notes'),
        btnInspFilterAudit: document.getElementById('btn-insp-filter-audit'),
        btnInspCopyStepJson: document.getElementById('btn-insp-copy-step-json'),

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
        bindKeyboardShortcuts();
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
        // Tab Navigation
        if (elements.tabBtnLogs) {
            elements.tabBtnLogs.addEventListener('click', () => switchTab('logs-view'));
        }
        if (elements.tabBtnJourney) {
            elements.tabBtnJourney.addEventListener('click', () => switchTab('journey-view'));
        }

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
                if (state.activeTab === 'logs-view') {
                    fetchLogs(state.currentPage);
                } else if (state.activeTab === 'journey-view' && elements.journeyInputItem && elements.journeyInputItem.value.trim()) {
                    fetchProvenance();
                }
                showToast('Refreshed telemetry and data.');
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

        // Search & Filter Form (Audit View)
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
            elements.selectDim.addEventListener('change', () => syncChipsUI());
        }
        if (elements.selectTimeframe) {
            elements.selectTimeframe.addEventListener('change', () => syncChipsUI());
        }

        // Quick-Filter Query Chips
        bindQuickFilterChips();

        // Pagination Controls
        if (elements.btnPageFirst) elements.btnPageFirst.addEventListener('click', () => fetchLogs(1));
        if (elements.btnPagePrev) elements.btnPagePrev.addEventListener('click', () => fetchLogs(state.currentPage - 1));
        if (elements.btnPageNext) elements.btnPageNext.addEventListener('click', () => fetchLogs(state.currentPage + 1));
        if (elements.btnPageLast) elements.btnPageLast.addEventListener('click', () => fetchLogs(state.totalPages));

        // Journey Form & Actions
        if (elements.journeyForm) {
            elements.journeyForm.addEventListener('submit', (e) => {
                e.preventDefault();
                fetchProvenance();
            });
        }

        if (elements.btnJourneyReset) {
            elements.btnJourneyReset.addEventListener('click', resetJourneyForm);
        }

        if (elements.btnExportGraphJson) {
            elements.btnExportGraphJson.addEventListener('click', () => {
                if (state.provenanceGraph) {
                    copyToClipboard(JSON.stringify(state.provenanceGraph, null, 2), 'Copied full journey graph JSON');
                } else {
                    showToast('No active journey graph to export', 'warning');
                }
            });
        }

        // Step Inspector Actions
        if (elements.btnInspFilterAudit) {
            elements.btnInspFilterAudit.addEventListener('click', () => {
                if (state.provenanceGraph && state.selectedStepIndex !== null && state.provenanceGraph.nodes) {
                    const node = state.provenanceGraph.nodes[state.selectedStepIndex];
                    if (node) {
                        switchTab('logs-view');
                        if (elements.inputX) elements.inputX.value = node.x;
                        if (elements.inputY) elements.inputY.value = node.y;
                        if (elements.inputZ) elements.inputZ.value = node.z;
                        if (elements.selectDim) elements.selectDim.value = node.dimension || '';
                        if (elements.inputItem) elements.inputItem.value = node.itemId || '';
                        syncChipsUI();
                        readFiltersFromUI();
                        state.activeQueryId = null;
                        fetchLogs(1);
                        showToast(`Filtering audit logs for container (${node.x}, ${node.y}, ${node.z})`);
                    }
                }
            });
        }

        if (elements.btnInspCopyStepJson) {
            elements.btnInspCopyStepJson.addEventListener('click', () => {
                if (state.provenanceGraph && state.selectedStepIndex !== null && state.provenanceGraph.nodes) {
                    const node = state.provenanceGraph.nodes[state.selectedStepIndex];
                    if (node) {
                        copyToClipboard(JSON.stringify(node, null, 2), `Copied Step #${node.stepIndex + 1} JSON`);
                    }
                }
            });
        }

        // Tab Visibility Lifecycle Management
        document.addEventListener('visibilitychange', () => {
            if (document.hidden) {
                if (state.statsTimer) {
                    clearInterval(state.statsTimer);
                    state.statsTimer = null;
                }
                updateLiveIndicatorUI();
            } else {
                if (!state.isRateLimited && state.autoTailInterval > 0) {
                    fetchStats();
                    if (state.activeTab === 'logs-view' && state.currentPage === 1 && (!elements.authModal || elements.authModal.classList.contains('hidden'))) {
                        fetchLogsSilently(1);
                    }
                    setupAutoTailTimer();
                } else {
                    updateLiveIndicatorUI();
                }
            }
        });
    }

    // Tab Switching
    function switchTab(tabId) {
        state.activeTab = tabId;
        if (elements.tabBtnLogs) elements.tabBtnLogs.classList.toggle('active', tabId === 'logs-view');
        if (elements.tabBtnJourney) elements.tabBtnJourney.classList.toggle('active', tabId === 'journey-view');
        if (elements.logsView) elements.logsView.classList.toggle('hidden', tabId !== 'logs-view');
        if (elements.journeyView) elements.journeyView.classList.toggle('hidden', tabId !== 'journey-view');
    }

    // Direct Trace Helper
    function traceItemJourney(itemId, x, y, z, dim, fingerprint) {
        if (!itemId) return;
        switchTab('journey-view');

        if (elements.journeyInputItem) elements.journeyInputItem.value = itemId;
        if (elements.journeyInputX) elements.journeyInputX.value = (x !== undefined && x !== null && x !== '-') ? x : '';
        if (elements.journeyInputY) elements.journeyInputY.value = (y !== undefined && y !== null && y !== '-') ? y : '';
        if (elements.journeyInputZ) elements.journeyInputZ.value = (z !== undefined && z !== null && z !== '-') ? z : '';
        if (elements.journeySelectDim) elements.journeySelectDim.value = dim || 'minecraft:overworld';
        if (elements.journeyInputFp) elements.journeyInputFp.value = (fingerprint && fingerprint !== '0' && fingerprint !== 0) ? fingerprint : '';

        fetchProvenance();
        showToast(`Tracing journey for ${formatItemName(itemId)}...`);
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

    // Live Stream Auto-Tail Lifecycle & Indicator State
    function updateLiveIndicatorUI() {
        const liveIndicator = elements.liveIndicator || document.querySelector('.live-indicator');
        if (!liveIndicator) return;

        const isPaused = state.autoTailInterval <= 0 || state.isRateLimited || document.hidden;
        if (isPaused) {
            liveIndicator.classList.add('paused');
            liveIndicator.classList.remove('active');
            if (state.isRateLimited) {
                liveIndicator.title = 'Auto-tail paused: Server rate limit reached (resuming in 60s)';
            } else if (document.hidden) {
                liveIndicator.title = 'Auto-tail paused: Tab in background';
            } else {
                liveIndicator.title = 'Auto-tail stream paused';
            }
        } else {
            liveIndicator.classList.remove('paused');
            liveIndicator.classList.add('active');
            liveIndicator.title = `Live stream active (${state.autoTailInterval / 1000}s interval)`;
        }
    }

    function setupAutoTailTimer() {
        if (state.statsTimer) {
            clearInterval(state.statsTimer);
            state.statsTimer = null;
        }

        updateLiveIndicatorUI();

        if (state.autoTailInterval <= 0 || state.isRateLimited || document.hidden) {
            return;
        }

        state.statsTimer = setInterval(() => {
            if (document.hidden || state.isRateLimited) return;

            fetchStats();
            if (state.activeTab === 'logs-view' && state.currentPage === 1 && (!elements.authModal || elements.authModal.classList.contains('hidden'))) {
                fetchLogsSilently(1);
            }
        }, state.autoTailInterval);
    }

    function handleRateLimitBackoff() {
        if (state.statsTimer) {
            clearInterval(state.statsTimer);
            state.statsTimer = null;
        }

        state.isRateLimited = true;
        updateLiveIndicatorUI();
        showToast('Auto-tail paused: Server rate limit reached. Resuming in 60s.', 'warning');

        if (state.rateLimitTimer) {
            clearTimeout(state.rateLimitTimer);
        }

        state.rateLimitTimer = setTimeout(() => {
            state.isRateLimited = false;
            state.rateLimitTimer = null;
            showToast('Resuming auto-tail stream...');
            setupAutoTailTimer();
        }, 60000);
    }

    // Global Keyboard Shortcuts
    function bindKeyboardShortcuts() {
        window.addEventListener('keydown', (e) => {
            const activeEl = document.activeElement;
            const isInputActive = activeEl && (
                activeEl.tagName === 'INPUT' ||
                activeEl.tagName === 'TEXTAREA' ||
                activeEl.tagName === 'SELECT' ||
                activeEl.isContentEditable
            );

            // Escape key handler: always active
            if (e.key === 'Escape') {
                let handled = false;

                if (elements.authModal && !elements.authModal.classList.contains('hidden')) {
                    closeAuthModal();
                    handled = true;
                }

                const expandedDetailRows = document.querySelectorAll('.row-detail-expanded');
                if (expandedDetailRows.length > 0) {
                    expandedDetailRows.forEach(row => {
                        const parentRow = row.previousElementSibling;
                        if (parentRow && parentRow.classList.contains('log-row')) {
                            parentRow.classList.remove('is-expanded', 'expanded');
                            const chevron = parentRow.querySelector('.chevron-icon');
                            if (chevron) chevron.classList.remove('expanded');
                        }
                        row.remove();
                    });
                    handled = true;
                }

                if (isInputActive) {
                    activeEl.blur();
                    handled = true;
                }

                if (handled) {
                    e.preventDefault();
                }
                return;
            }

            if (isInputActive || e.ctrlKey || e.altKey || e.metaKey) {
                return;
            }

            // '/' : Focus player search input or item input
            if (e.key === '/') {
                e.preventDefault();
                if (state.activeTab === 'logs-view' && elements.inputPlayer) {
                    elements.inputPlayer.focus();
                    elements.inputPlayer.select();
                } else if (state.activeTab === 'journey-view' && elements.journeyInputItem) {
                    elements.journeyInputItem.focus();
                    elements.journeyInputItem.select();
                }
                return;
            }

            // 'r' or 'R' : Refresh
            if (e.key === 'r' || e.key === 'R') {
                e.preventDefault();
                fetchStats();
                if (state.activeTab === 'logs-view') {
                    fetchLogs(state.currentPage);
                } else if (state.activeTab === 'journey-view') {
                    fetchProvenance();
                }
                showToast('Refreshed data.');
                return;
            }

            // 'ArrowLeft' : Previous page
            if (e.key === 'ArrowLeft' && state.activeTab === 'logs-view') {
                if (state.currentPage > 1) {
                    e.preventDefault();
                    fetchLogs(state.currentPage - 1);
                }
                return;
            }

            // 'ArrowRight' : Next page
            if (e.key === 'ArrowRight' && state.activeTab === 'logs-view') {
                if (state.currentPage < state.totalPages) {
                    e.preventDefault();
                    fetchLogs(state.currentPage + 1);
                }
                return;
            }
        });
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

    // Filter Form Reading & Reset (Audit View)
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

    // Query & Fetch Logs (Audit View)
    async function fetchLogs(targetPage) {
        renderTableLoading();
        await executeFetchLogs(targetPage, false);
    }

    async function fetchLogsSilently(targetPage) {
        await executeFetchLogs(targetPage, true);
    }

    async function executeFetchLogs(targetPage, isSilent = false) {
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
                if (!isSilent) {
                    renderTableEmpty('Authentication required. Click "Auth Token" in the top-right to enter your secretToken.');
                    openAuthModal();
                }
                return;
            }

            if (resp.status === 429) {
                handleRateLimitBackoff();
                if (!isSilent) {
                    renderTableEmpty('Server rate limit reached (HTTP 429). Auto-tail paused for 60s.');
                }
                return;
            }

            if (!resp.ok) {
                const errData = await resp.json().catch(() => ({}));
                if (!isSilent) {
                    renderTableEmpty('Error fetching logs: ' + (errData.error || ('HTTP ' + resp.status)));
                }
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
            if (!isSilent) {
                renderTableEmpty('Failed to connect to server: ' + err.message);
            }
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

    // Render Table Data & Rows (Audit View)
    function renderTableData(records) {
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

        const expandedKeys = new Set();
        document.querySelectorAll('.log-row.is-expanded').forEach(r => {
            if (r.dataset.txKey) expandedKeys.add(r.dataset.txKey);
        });

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
            if (act === 'TAKE' || act === 'PICKUP') actClass = 'action-take';
            else if (act === 'PUT' || act === 'PLACE') actClass = 'action-put';
            else if (act === 'CLEAR') actClass = 'action-clear';

            const slotNum = rec.slot != null ? (rec.slot < 10 ? '0' + rec.slot : rec.slot) : '--';

            const rawItemId = rec.itemId || rec.item || '';
            const itemName = formatItemName(rawItemId);

            const deltaVal = rec.delta != null ? rec.delta : 0;
            const deltaClass = deltaVal >= 0 ? 'delta-pos' : 'delta-neg';
            const deltaSign = deltaVal > 0 ? '+' : '';

            const txKey = String(rec.transactionId || rec.uuid || rec.id || `${timeObj}_${posX}_${posY}_${posZ}_${slotNum}`);

            const tr = document.createElement('tr');
            tr.className = 'log-row';
            tr.dataset.index = String(idx);
            tr.dataset.txKey = txKey;

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
                    <button type="button" class="btn-inspect-mini btn-trace-item" title="Trace Item Journey / Provenance" aria-label="Trace Journey">
                        <svg class="svg-icon-xs text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <circle cx="6" cy="6" r="3"/>
                            <circle cx="18" cy="18" r="3"/>
                            <path d="M6 9v12a2 2 0 0 0 2 2h10"/>
                        </svg>
                    </button>
                </td>
            `;

            // Expansion & Inspection Handlers
            const btnExpand = tr.querySelector('.btn-row-expand');
            const btnInspect = tr.querySelector('.btn-quick-inspect');
            const btnTrace = tr.querySelector('.btn-trace-item');
            const coordBadge = tr.querySelector('.badge-coord');

            function toggleRowDetail() {
                const nextElem = tr.nextElementSibling;
                const isCurrentlyExpanded = nextElem && nextElem.classList.contains('row-detail-expanded');

                if (isCurrentlyExpanded) {
                    tr.classList.remove('is-expanded', 'expanded');
                    const chevron = tr.querySelector('.chevron-icon');
                    if (chevron) chevron.classList.remove('expanded');
                    nextElem.remove();
                } else {
                    tr.classList.add('is-expanded', 'expanded');
                    const chevron = tr.querySelector('.chevron-icon');
                    if (chevron) chevron.classList.add('expanded');

                    const detailRow = createDetailRow(rec);
                    tr.insertAdjacentElement('afterend', detailRow);
                }
            }

            tr.addEventListener('click', (e) => {
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

            if (btnTrace) {
                btnTrace.addEventListener('click', (e) => {
                    e.stopPropagation();
                    traceItemJourney(rec.itemId || rec.item, rec.x, rec.y, rec.z, rec.dimension, rec.metadataFingerprint);
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

            if (expandedKeys.has(txKey)) {
                toggleRowDetail();
            }
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
        const actorUuid = rec.actorUuid || 'N/A';
        const slotNum = rec.slot != null ? (rec.slot < 10 ? '0' + rec.slot : rec.slot) : null;
        const slotIndex = slotNum != null ? (rec.slot >= 27 ? `#${slotNum} (Right)` : `#${slotNum} (Left)`) : '#-';
        const prevItem = rec.prevItem || (rec.delta < 0 ? (rec.itemId || rec.item || 'empty') : 'empty');
        const currItem = rec.itemId || rec.item || 'empty';

        const deltaVal = rec.delta != null ? rec.delta : 0;
        const deltaClass = deltaVal >= 0 ? 'delta-pos' : 'delta-neg';
        const deltaSign = deltaVal > 0 ? '+' : '';

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

        const btnTraceRowItem = detailTr.querySelector('.btn-trace-row-item');
        if (btnTraceRowItem) {
            btnTraceRowItem.addEventListener('click', (e) => {
                e.stopPropagation();
                traceItemJourney(rec.itemId || rec.item, rec.x, rec.y, rec.z, rec.dimension, rec.metadataFingerprint);
            });
        }

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

    // =========================================================================
    // Item Journey / Provenance Graph Resolution & Interactive Visualizer
    // =========================================================================

    function resetJourneyForm() {
        if (elements.journeyInputItem) elements.journeyInputItem.value = '';
        if (elements.journeyInputX) elements.journeyInputX.value = '';
        if (elements.journeyInputY) elements.journeyInputY.value = '';
        if (elements.journeyInputZ) elements.journeyInputZ.value = '';
        if (elements.journeySelectDim) elements.journeySelectDim.value = 'minecraft:overworld';
        if (elements.journeyInputFp) elements.journeyInputFp.value = '';
        if (elements.journeySelectHops) elements.journeySelectHops.value = '50';

        state.provenanceGraph = null;
        state.selectedStepIndex = null;

        if (elements.journeyEmptyState) elements.journeyEmptyState.classList.remove('hidden');
        if (elements.journeySvgContainer) elements.journeySvgContainer.classList.add('hidden');
        if (elements.journeyTargetId) elements.journeyTargetId.textContent = 'None';
        if (elements.journeyTotalSteps) elements.journeyTotalSteps.textContent = '0';
        if (elements.journeyOverallConfidence) {
            elements.journeyOverallConfidence.className = 'confidence-badge confidence-exact';
            elements.journeyOverallConfidence.textContent = 'EXACT';
        }

        if (elements.inspectorPlaceholder) elements.inspectorPlaceholder.classList.remove('hidden');
        if (elements.inspectorDetails) elements.inspectorDetails.classList.add('hidden');
    }

    async function fetchProvenance() {
        const itemVal = elements.journeyInputItem ? elements.journeyInputItem.value.trim() : '';
        if (!itemVal) {
            showToast('Please specify a target item identifier (e.g. minecraft:diamond)', 'warning');
            if (elements.journeyInputItem) elements.journeyInputItem.focus();
            return;
        }

        const queryParams = new URLSearchParams();
        queryParams.set('item', itemVal);

        const xVal = elements.journeyInputX ? elements.journeyInputX.value.trim() : '';
        const yVal = elements.journeyInputY ? elements.journeyInputY.value.trim() : '';
        const zVal = elements.journeyInputZ ? elements.journeyInputZ.value.trim() : '';

        if (xVal !== '' && yVal !== '' && zVal !== '') {
            queryParams.set('x', xVal);
            queryParams.set('y', yVal);
            queryParams.set('z', zVal);
        }

        const dimVal = elements.journeySelectDim ? elements.journeySelectDim.value : '';
        if (dimVal) queryParams.set('dim', dimVal);

        const fpVal = elements.journeyInputFp ? elements.journeyInputFp.value.trim() : '';
        if (fpVal && fpVal !== '0') queryParams.set('fingerprint', fpVal);

        const hopsVal = elements.journeySelectHops ? elements.journeySelectHops.value : '50';
        queryParams.set('maxHops', hopsVal);

        try {
            const resp = await fetch('/api/v1/provenance?' + queryParams.toString(), {
                headers: getAuthHeaders()
            });

            if (resp.status === 401) {
                showToast('Authentication required. Opening token settings...', 'warning');
                openAuthModal();
                return;
            }

            if (!resp.ok) {
                const errData = await resp.json().catch(() => ({}));
                showToast('Provenance Error: ' + (errData.error || ('HTTP ' + resp.status)), 'error');
                return;
            }

            const graph = await resp.json();
            state.provenanceGraph = graph;
            renderProvenanceGraph(graph);
        } catch (err) {
            showToast('Failed to resolve provenance graph: ' + err.message, 'error');
        }
    }

    function renderProvenanceGraph(graph) {
        if (!graph || !graph.nodes || graph.nodes.length === 0) {
            if (elements.journeyEmptyState) elements.journeyEmptyState.classList.remove('hidden');
            if (elements.journeySvgContainer) elements.journeySvgContainer.classList.add('hidden');
            if (elements.journeyTargetId) elements.journeyTargetId.textContent = graph ? graph.targetItemId : 'None';
            if (elements.journeyTotalSteps) elements.journeyTotalSteps.textContent = '0';
            if (elements.inspectorPlaceholder) elements.inspectorPlaceholder.classList.remove('hidden');
            if (elements.inspectorDetails) elements.inspectorDetails.classList.add('hidden');
            showToast('No chain of custody records found for this item.', 'info');
            return;
        }

        if (elements.journeyEmptyState) elements.journeyEmptyState.classList.add('hidden');
        if (elements.journeySvgContainer) elements.journeySvgContainer.classList.remove('hidden');

        // Update Summary Banner
        if (elements.journeyTargetId) {
            elements.journeyTargetId.textContent = `${formatItemName(graph.targetItemId)} (${graph.targetItemId})`;
        }
        if (elements.journeyTotalSteps) {
            elements.journeyTotalSteps.textContent = graph.totalSteps;
        }
        if (elements.journeyOverallConfidence) {
            const conf = (graph.overallConfidence || 'PROBABLE').toUpperCase();
            let confClass = 'confidence-probable';
            let confLabel = 'PROBABLE';
            if (conf.includes('EXACT')) {
                confClass = 'confidence-exact';
                confLabel = 'EXACT';
            } else if (conf.includes('HIGH')) {
                confClass = 'confidence-high';
                confLabel = 'HIGH';
            }
            elements.journeyOverallConfidence.className = `confidence-badge ${confClass}`;
            elements.journeyOverallConfidence.textContent = confLabel;
        }

        // SVG Canvas Rendering
        const svg = elements.journeySvg;
        if (!svg) return;

        const nodes = graph.nodes;
        const edges = graph.edges || [];

        const nodeWidth = 260;
        const nodeHeight = 110;
        const gapX = 140;
        const startX = 40;
        const startY = 60;

        const totalWidth = Math.max(900, startX * 2 + nodes.length * nodeWidth + (nodes.length - 1) * gapX);
        const totalHeight = 260;

        svg.setAttribute('viewBox', `0 0 ${totalWidth} ${totalHeight}`);
        svg.style.width = `${totalWidth}px`;
        svg.style.height = `${totalHeight}px`;

        // Generate Node Coordinates Map
        const nodeCoords = [];
        for (let i = 0; i < nodes.length; i++) {
            const nx = startX + i * (nodeWidth + gapX);
            const ny = startY;
            nodeCoords.push({ x: nx, y: ny, centerX: nx + nodeWidth / 2, centerY: ny + nodeHeight / 2 });
        }

        // Build SVG Elements
        let svgContent = `
            <defs>
                <marker id="arrow-exact" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                    <path d="M 0 1 L 10 5 L 0 9 z" fill="#2ecc71"/>
                </marker>
                <marker id="arrow-high" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                    <path d="M 0 1 L 10 5 L 0 9 z" fill="#f1c40f"/>
                </marker>
                <marker id="arrow-probable" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                    <path d="M 0 1 L 10 5 L 0 9 z" fill="#e67e22"/>
                </marker>
            </defs>
        `;

        // Render Edges
        edges.forEach(edge => {
            const fromC = nodeCoords[edge.fromIndex];
            const toC = nodeCoords[edge.toIndex];
            if (!fromC || !toC) return;

            const x1 = fromC.x + nodeWidth;
            const y1 = fromC.centerY;
            const x2 = toC.x;
            const y2 = toC.centerY;

            const edgeConf = (edge.confidence || 'PROBABLE').toUpperCase();
            let edgeClass = 'edge-probable';
            let markerId = 'arrow-probable';
            if (edgeConf.includes('EXACT')) {
                edgeClass = 'edge-exact';
                markerId = 'arrow-exact';
            } else if (edgeConf.includes('HIGH')) {
                edgeClass = 'edge-high';
                markerId = 'arrow-high';
            }

            const midX = (x1 + x2) / 2;
            const midY = (y1 + y2) / 2;

            const pathD = `M ${x1} ${y1} C ${x1 + 40} ${y1}, ${x2 - 40} ${y2}, ${x2} ${y2}`;

            const transitionStr = formatTransitionType(edge.transitionType);
            const timeDeltaStr = '+' + formatTimeDelta(edge.timeDeltaMs);

            svgContent += `
                <g class="svg-edge-group">
                    <path class="svg-edge-path ${edgeClass}" d="${pathD}" marker-end="url(#${markerId})"/>
                    <rect class="svg-edge-label-bg" x="${midX - 55}" y="${midY - 18}" width="110" height="36"/>
                    <text class="svg-edge-text-type" x="${midX}" y="${midY - 6}">${escapeHtml(transitionStr)}</text>
                    <text class="svg-edge-text-time" x="${midX}" y="${midY + 8}">${escapeHtml(timeDeltaStr)}</text>
                </g>
            `;
        });

        // Render Nodes
        nodes.forEach((node, i) => {
            const coord = nodeCoords[i];
            const nodeConf = (node.confidence || 'PROBABLE').toUpperCase();
            let confClass = 'node-probable';
            if (nodeConf.includes('EXACT')) confClass = 'node-exact';
            else if (nodeConf.includes('HIGH')) confClass = 'node-high';

            const act = (node.actionType || 'INTERACT').toUpperCase();
            let actBg = '#10b981';
            if (act === 'TAKE' || act === 'PICKUP') actBg = '#f43f5e';
            else if (act === 'PUT' || act === 'PLACE') actBg = '#10b981';

            const deltaVal = node.deltaQuantity != null ? node.deltaQuantity : 0;
            const deltaClass = deltaVal >= 0 ? 'text-success' : 'text-danger';
            const deltaSign = deltaVal > 0 ? '+' : '';

            const shortDim = (node.dimension || 'overworld').replace('minecraft:', '');

            svgContent += `
                <g class="svg-node-group" data-step-index="${i}" transform="translate(${coord.x}, ${coord.y})">
                    <rect class="svg-node-box ${confClass}" width="${nodeWidth}" height="${nodeHeight}"/>
                    
                    <!-- Step Index Pill -->
                    <rect class="svg-node-step-pill" x="12" y="12" width="28" height="20"/>
                    <text class="svg-node-step-text" x="26" y="22">#${node.stepIndex + 1}</text>
                    
                    <!-- Actor Name -->
                    <text class="svg-node-actor-name" x="48" y="22">${escapeHtml(node.actorName || 'Unknown')}</text>
                    
                    <!-- Delta Quantity -->
                    <text class="svg-node-delta-text ${deltaClass}" x="${nodeWidth - 14}" y="22">${deltaSign}${deltaVal}</text>
                    
                    <!-- Action Pill -->
                    <rect class="svg-node-action-pill" x="12" y="44" width="60" height="18" fill="${actBg}"/>
                    <text class="svg-node-action-text" x="42" y="53" fill="#ffffff">${escapeHtml(act)}</text>
                    
                    <!-- Coordinates & Dimension -->
                    <text class="svg-node-coord-text" x="80" y="53">(${node.x}, ${node.y}, ${node.z}) • ${escapeHtml(shortDim)}</text>
                    
                    <!-- Notes / Summary Line -->
                    <text class="svg-node-coord-text" x="12" y="84" fill="#94a3b8" style="font-size: 10px;">${escapeHtml(formatNodeSummary(node))}</text>
                </g>
            `;
        });

        svg.innerHTML = svgContent;

        // Bind Click Handlers on Nodes
        svg.querySelectorAll('.svg-node-group').forEach(group => {
            group.addEventListener('click', () => {
                const stepIdx = parseInt(group.dataset.stepIndex, 10);
                selectStep(stepIdx);
            });
        });

        // Select initial node (first step)
        selectStep(0);
    }

    function selectStep(index) {
        if (!state.provenanceGraph || !state.provenanceGraph.nodes) return;
        const nodes = state.provenanceGraph.nodes;
        if (index < 0 || index >= nodes.length) return;

        state.selectedStepIndex = index;
        const node = nodes[index];

        // Update selected state in SVG
        const svg = elements.journeySvg;
        if (svg) {
            svg.querySelectorAll('.svg-node-group').forEach(g => {
                const isSelected = parseInt(g.dataset.stepIndex, 10) === index;
                g.classList.toggle('is-selected', isSelected);
            });
        }

        // Populate Inspector Card
        if (elements.inspectorPlaceholder) elements.inspectorPlaceholder.classList.add('hidden');
        if (elements.inspectorDetails) elements.inspectorDetails.classList.remove('hidden');

        if (elements.inspectorStepTitle) {
            elements.inspectorStepTitle.textContent = `Step #${node.stepIndex + 1} Inspector`;
        }

        if (elements.inspectorConfidenceBadge) {
            const conf = (node.confidence || 'PROBABLE').toUpperCase();
            let confClass = 'confidence-probable';
            let confLabel = 'PROBABLE';
            if (conf.includes('EXACT')) {
                confClass = 'confidence-exact';
                confLabel = 'EXACT';
            } else if (conf.includes('HIGH')) {
                confClass = 'confidence-high';
                confLabel = 'HIGH';
            }
            elements.inspectorConfidenceBadge.className = `confidence-badge ${confClass}`;
            elements.inspectorConfidenceBadge.textContent = confLabel;
        }

        if (elements.inspStepSeq) {
            elements.inspStepSeq.textContent = `Step #${node.stepIndex + 1} (Sequence #${node.sequenceId})`;
        }

        if (elements.inspTimestamp) {
            const iso = new Date(node.timestampMs).toISOString();
            elements.inspTimestamp.textContent = `${formatTimestamp(node.timestampMs)} (${iso})`;
        }

        if (elements.inspActionActor) {
            elements.inspActionActor.textContent = `${node.actionType} by ${node.actorName} (${node.actorType})`;
        }

        if (elements.inspActorUuid) {
            elements.inspActorUuid.textContent = node.actorUuid || 'None (Automation / World)';
        }

        if (elements.inspContainerCoord) {
            elements.inspContainerCoord.textContent = `X: ${node.x}, Y: ${node.y}, Z: ${node.z}`;
        }

        if (elements.inspDimension) {
            elements.inspDimension.textContent = node.dimension;
        }

        if (elements.inspDelta) {
            const deltaSign = node.deltaQuantity > 0 ? '+' : '';
            const deltaClass = node.deltaQuantity >= 0 ? 'delta-pos' : 'delta-neg';
            elements.inspDelta.innerHTML = `<span class="delta-pill ${deltaClass}">${deltaSign}${node.deltaQuantity}</span>`;
        }

        if (elements.inspFingerprint) {
            elements.inspFingerprint.textContent = (node.metadataFingerprint && node.metadataFingerprint !== 0)
                ? String(node.metadataFingerprint)
                : '0L (Fungible Commodity)';
        }

        if (elements.inspNotes) {
            elements.inspNotes.textContent = node.notes || 'Direct inventory custody transition.';
        }
    }

    function formatTransitionType(type) {
        if (!type) return 'TRANSITION';
        return type.replace(/_/g, ' ');
    }

    function formatNodeSummary(node) {
        const actionStr = node.deltaQuantity < 0 ? 'Extracted' : 'Deposited';
        return `${actionStr} ${Math.abs(node.deltaQuantity)} ${formatItemName(node.itemId)}`;
    }

    function formatTimeDelta(ms) {
        if (ms == null || ms === 0) return '0s';
        const sec = Math.floor(ms / 1000);
        if (sec < 60) return `${sec}s`;
        const min = Math.floor(sec / 60);
        const remSec = sec % 60;
        if (min < 60) return remSec > 0 ? `${min}m ${remSec}s` : `${min}m`;
        const hrs = Math.floor(min / 60);
        const remMin = min % 60;
        if (hrs < 24) return `${hrs}h ${remMin}m`;
        const days = Math.floor(hrs / 24);
        const remHrs = hrs % 24;
        return `${days}d ${remHrs}h`;
    }

    // Pagination Controls (Audit View)
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
        if (state.activeTab === 'logs-view') {
            fetchLogs(1);
        } else if (state.activeTab === 'journey-view') {
            fetchProvenance();
        }
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

    function showToast(msg, type = 'info') {
        if (!elements.toastContainer) return;
        const toast = document.createElement('div');
        toast.className = `toast ${type !== 'info' ? 'toast-' + type : ''}`.trim();
        toast.textContent = msg;
        elements.toastContainer.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 3600);
    }

    // Start App on DOM Ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
