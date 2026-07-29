package org.otel.agent.webserver;

final class ConfigPage {
  private ConfigPage() {}

  private static final String HTML =
      """
      <!DOCTYPE html>
      <html lang="en">
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>OTel Custom Agent — Configuration</title>
        <style>
          :root {
            --bg: #f7f8fa;
            --card: #ffffff;
            --border: #d1d5db;
            --text: #1f2937;
            --muted: #6b7280;
            --accent: #2563eb;
            --accent-hover: #1d4ed8;
            --danger: #dc2626;
            --code-bg: #f3f4f6;
          }
          * { box-sizing: border-box; margin: 0; padding: 0; }
          body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background: var(--bg);
            color: var(--text);
            line-height: 1.6;
            padding: 2rem;
            max-width: 860px;
            margin: 0 auto;
          }
          h1 { font-size: 1.4rem; font-weight: 600; margin-bottom: .25rem; }
          .subtitle { color: var(--muted); font-size: .9rem; margin-bottom: 1.5rem; }
          .card {
            background: var(--card);
            border: 1px solid var(--border);
            border-radius: .5rem;
            padding: 1.25rem;
            margin-bottom: 1.25rem;
          }
          .card h2 { font-size: 1rem; font-weight: 600; margin-bottom: .75rem; }
          pre {
            background: var(--code-bg);
            border: 1px solid var(--border);
            border-radius: .375rem;
            padding: .75rem;
            overflow-x: auto;
            font-size: .85rem;
            white-space: pre-wrap;
            word-break: break-word;
            min-height: 3rem;
            color: var(--text);
          }
          textarea {
            width: 100%;
            min-height: 12rem;
            border: 1px solid var(--border);
            border-radius: .375rem;
            padding: .75rem;
            font-family: "SF Mono", "Monaco", "Consolas", monospace;
            font-size: .85rem;
            resize: vertical;
          }
          textarea:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
          .actions { display: flex; gap: .75rem; margin-top: 1rem; }
          button {
            padding: .5rem 1.25rem;
            border: 1px solid var(--border);
            border-radius: .375rem;
            font-size: .875rem;
            font-weight: 500;
            cursor: pointer;
            background: var(--card);
            color: var(--text);
            transition: background .15s, border-color .15s;
          }
          button:hover { border-color: var(--accent); }
          button.primary {
            background: var(--accent);
            border-color: var(--accent);
            color: #fff;
          }
          button.primary:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
          #status {
            margin-top: 1rem;
            padding: .75rem;
            border-radius: .375rem;
            font-size: .875rem;
            display: none;
          }
          #status.ok { background: #ecfdf5; border: 1px solid #6ee7b7; color: #065f46; display: block; }
          #status.err { background: #fef2f2; border: 1px solid #fca5a5; color: #991b1b; display: block; }
        </style>
      </head>
      <body>
        <h1>OTel Custom Agent</h1>
        <p class="subtitle">Declarative span-attribute configuration</p>

        <section class="card">
          <h2>Current Configuration</h2>
          <pre id="current">Loading…</pre>
        </section>

        <section class="card">
          <h2>New Configuration</h2>
          <textarea id="xml" spellcheck="false" placeholder="Paste XML configuration here…"></textarea>
          <div class="actions">
            <button class="primary" id="activate" type="button">Activate</button>
            <button id="loadOriginal" type="button">Load Original</button>
          </div>
          <div id="status"></div>
        </section>

        <script>
          const current = document.getElementById('current');
          const xml = document.getElementById('xml');
          const status = document.getElementById('status');
          const activateBtn = document.getElementById('activate');
          const loadBtn = document.getElementById('loadOriginal');

          function showStatus(msg, ok) {
            status.textContent = msg;
            status.className = ok ? 'ok' : 'err';
          }

          function refreshCurrent() {
            fetch('/config/current')
              .then(r => r.ok ? r.text() : Promise.reject(r.status))
              .then(text => { current.textContent = text; })
              .catch(() => { current.textContent = 'No active configuration'; });
          }

          activateBtn.addEventListener('click', () => {
            fetch('/config', {
              method: 'POST',
              headers: { 'Content-Type': 'text/xml' },
              body: xml.value
            })
            .then(r => r.text().then(body => ({ status: r.status, body })))
            .then(({ status, body }) => {
              const ok = status === 200;
              showStatus(body, ok);
              if (ok) { refreshCurrent(); }
            })
            .catch(() => showStatus('Request failed', false));
          });

          loadBtn.addEventListener('click', () => {
            fetch('/config/original')
              .then(r => {
                if (!r.ok) { showStatus('No original configuration found', false); return null; }
                return r.text();
              })
              .then(text => { if (text) { xml.value = text; showStatus('Loaded original configuration', true); } })
              .catch(() => showStatus('Request failed', false));
          });

          refreshCurrent();
        </script>
      </body>
      </html>
      """;

  static String html() {
    return HTML;
  }
}
