const fs = require('node:fs');
const path = require('node:path');
const { parse, Kind, print } = require('graphql');

const root = path.resolve(__dirname, '..');
const sourceFiles = [
  'src/main/resources/schema/schema.graphqls',
  'src/main/resources/schema/universe.graphqls',
];
const sdl = sourceFiles
  .map(file => `# Source: ${file}\n${fs.readFileSync(path.join(root, file), 'utf8').trim()}`)
  .join('\n\n');
const document = parse(sdl);

const typeNode = node => {
  if (node.kind === Kind.NON_NULL_TYPE) return { kind: 'nonNull', ofType: typeNode(node.type) };
  if (node.kind === Kind.LIST_TYPE) return { kind: 'list', ofType: typeNode(node.type) };
  return { kind: 'named', name: node.name.value };
};
const directiveArg = (directive, name) => {
  const argument = directive?.arguments?.find(item => item.name.value === name);
  return argument?.value?.value ?? null;
};
const deprecation = node => {
  const directive = node.directives?.find(item => item.name.value === 'deprecated');
  return directive ? directiveArg(directive, 'reason') || 'No longer supported' : null;
};
const argumentsFor = node => (node.arguments || []).map(argument => ({
  name: argument.name.value,
  type: typeNode(argument.type),
  defaultValue: argument.defaultValue ? print(argument.defaultValue) : null,
  deprecated: deprecation(argument),
}));

const definitions = document.definitions
  .filter(definition => definition.name)
  .map(definition => {
    const kind = definition.kind.replace('_TYPE_DEFINITION', '').replace('_DEFINITION', '').toLowerCase();
    const base = { name: definition.name.value, kind };
    if (definition.kind === Kind.OBJECT_TYPE_DEFINITION || definition.kind === Kind.INTERFACE_TYPE_DEFINITION || definition.kind === Kind.INPUT_OBJECT_TYPE_DEFINITION) {
      base.fields = (definition.fields || []).map(field => ({
        name: field.name.value,
        type: typeNode(field.type),
        arguments: argumentsFor(field),
        deprecated: deprecation(field),
      }));
    }
    if (definition.kind === Kind.ENUM_TYPE_DEFINITION) {
      base.values = definition.values.map(value => ({ name: value.name.value, deprecated: deprecation(value) }));
    }
    if (definition.kind === Kind.UNION_TYPE_DEFINITION) {
      base.members = definition.types.map(member => member.name.value);
    }
    return base;
  });

const query = definitions.find(definition => definition.name === 'Query');
const objects = definitions.filter(definition => definition.kind === 'object' && definition.name !== 'Query');
const enums = definitions.filter(definition => definition.kind === 'enum');
const scalars = definitions.filter(definition => definition.kind === 'scalar');
const fieldCount = definitions.reduce((total, definition) => total + (definition.fields?.length || 0), 0);
const deprecatedCount = definitions.reduce((total, definition) =>
  total + (definition.fields?.filter(field => field.deprecated).length || 0) +
  (definition.values?.filter(value => value.deprecated).length || 0), 0);
const payload = JSON.stringify({ definitions, query, objects, enums, scalars, sdl, sourceFiles, fieldCount, deprecatedCount })
  .replaceAll('<', '\\u003c');

const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Complete browsable GraphQL schema for Livia, including deprecated compatibility fields.">
  <title>Livia — Complete GraphQL Schema</title>
  <style>
    :root {
      color-scheme: light;
      --paper: #f4f0e7;
      --paper-deep: #e7dfd1;
      --ink: #18211e;
      --muted: #5e6863;
      --line: #c9c1b2;
      --accent: #006b58;
      --accent-soft: #d8ebe5;
      --deprecated: #9a3412;
      --deprecated-soft: #ffedd5;
      --code: #10251f;
      --code-ink: #e8f3ee;
      --focus: #007f68;
      --serif: Georgia, 'Times New Roman', serif;
      --sans: Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      --mono: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
    }
    * { box-sizing: border-box; }
    html { scroll-behavior: smooth; }
    body {
      margin: 0;
      background: var(--paper);
      color: var(--ink);
      font-family: var(--sans);
      line-height: 1.5;
    }
    ::selection { background: #aad8ca; color: #0b211b; }
    :focus-visible { outline: 3px solid var(--focus); outline-offset: 3px; }
    button, input { font: inherit; }
    button { color: inherit; }
    .masthead {
      border-bottom: 1px solid var(--line);
      padding: 34px clamp(20px, 5vw, 72px) 28px;
      background: #faf7f0;
    }
    .masthead-row { display: flex; justify-content: space-between; align-items: end; gap: 28px; }
    h1 {
      max-width: 850px;
      margin: 0;
      font-family: var(--serif);
      font-size: clamp(2.4rem, 6vw, 5.6rem);
      line-height: .94;
      letter-spacing: -.035em;
      font-weight: 500;
      text-wrap: balance;
    }
    .intro { max-width: 68ch; margin: 18px 0 0; color: var(--muted); font-size: 1.02rem; }
    .status { flex: 0 0 auto; color: var(--accent); font-weight: 750; letter-spacing: .02em; }
    .ledger {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      margin-top: 32px;
      border-top: 1px solid var(--line);
      border-bottom: 1px solid var(--line);
    }
    .ledger div { padding: 14px 16px 15px 0; }
    .ledger div + div { padding-left: 16px; border-left: 1px solid var(--line); }
    .ledger strong { display: block; font: 600 1.5rem/1 var(--serif); font-variant-numeric: tabular-nums; }
    .ledger span { display: block; margin-top: 5px; color: var(--muted); font-size: .76rem; text-transform: uppercase; letter-spacing: .09em; }
    .toolbar {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px clamp(20px, 5vw, 72px);
      background: color-mix(in srgb, #faf7f0 94%, transparent);
      border-bottom: 1px solid var(--line);
      backdrop-filter: blur(14px);
    }
    .search-wrap { position: relative; flex: 1 1 360px; max-width: 620px; }
    .search-wrap svg { position: absolute; left: 13px; top: 50%; transform: translateY(-50%); color: var(--muted); pointer-events: none; }
    #search {
      width: 100%;
      min-height: 42px;
      border: 1px solid var(--line);
      border-radius: 12px;
      padding: 9px 42px;
      background: #fffdf8;
      color: var(--ink);
    }
    #search::placeholder { color: #69736e; }
    #clear-search {
      position: absolute;
      right: 6px;
      top: 5px;
      width: 32px;
      height: 32px;
      display: none;
      border: 0;
      border-radius: 50%;
      background: transparent;
      cursor: pointer;
      font-size: 1.2rem;
    }
    .view-switch { display: flex; align-items: center; border-bottom: 1px solid var(--line); }
    .view-switch button, .copy-button {
      min-height: 40px;
      border: 0;
      background: transparent;
      padding: 8px 12px;
      cursor: pointer;
      font-weight: 700;
    }
    .view-switch button[aria-selected="true"] { color: var(--accent); box-shadow: inset 0 -3px var(--accent); }
    .copy-button { margin-left: auto; border: 1px solid var(--line); border-radius: 12px; background: #fffdf8; }
    .shell { display: grid; grid-template-columns: 260px minmax(0, 1fr); max-width: 1580px; margin: 0 auto; }
    .sidebar {
      position: sticky;
      top: 67px;
      height: calc(100vh - 67px);
      overflow: auto;
      padding: 34px 28px 60px;
      border-right: 1px solid var(--line);
      scrollbar-color: #8e978f transparent;
    }
    .nav-group + .nav-group { margin-top: 28px; }
    .nav-group h2 { margin: 0 0 8px; font-size: .72rem; text-transform: uppercase; letter-spacing: .12em; color: var(--muted); }
    .nav-group a { display: block; padding: 5px 0; color: var(--ink); text-decoration: none; font-size: .87rem; overflow-wrap: anywhere; }
    .nav-group a:hover { color: var(--accent); text-decoration: underline; text-underline-offset: 3px; }
    main { min-width: 0; padding: 44px clamp(24px, 5vw, 76px) 100px; }
    .section-heading { margin: 0 0 22px; font: 500 clamp(1.8rem, 3vw, 3rem)/1 var(--serif); letter-spacing: -.025em; }
    .section-note { max-width: 70ch; margin: -10px 0 30px; color: var(--muted); }
    .definition { scroll-margin-top: 92px; border-top: 1px solid var(--line); padding: 26px 0 34px; }
    .definition:last-child { border-bottom: 1px solid var(--line); }
    .definition-head { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
    .definition h3 { margin: 0; font: 500 clamp(1.35rem, 2vw, 2rem)/1.1 var(--serif); letter-spacing: -.015em; }
    .kind { color: var(--muted); font: 700 .68rem/1 var(--sans); text-transform: uppercase; letter-spacing: .11em; }
    .field-list { margin: 0; }
    .field {
      display: grid;
      grid-template-columns: minmax(180px, .75fr) minmax(220px, 1fr);
      gap: 20px;
      padding: 13px 0;
      border-top: 1px solid color-mix(in srgb, var(--line) 70%, transparent);
    }
    .field:first-child { border-top: 0; }
    .field dt, .field dd { margin: 0; min-width: 0; }
    code { font-family: var(--mono); font-size: .86rem; }
    .field-name { font-weight: 750; color: #163d32; }
    .args { display: block; margin-top: 5px; color: var(--muted); line-height: 1.65; }
    .return { text-align: right; overflow-wrap: anywhere; }
    .type-link { color: var(--accent); text-decoration: none; }
    .type-link:hover { text-decoration: underline; text-underline-offset: 3px; }
    .deprecated { display: inline-flex; align-items: center; margin-left: 8px; border-radius: 999px; padding: 2px 8px; background: var(--deprecated-soft); color: var(--deprecated); font: 750 .65rem/1.5 var(--sans); text-transform: uppercase; letter-spacing: .07em; }
    .reason { display: block; margin-top: 6px; color: var(--deprecated); font-size: .8rem; }
    .enum-values { display: flex; flex-wrap: wrap; gap: 8px; margin: 0; padding: 0; list-style: none; }
    .enum-values li { border: 1px solid var(--line); border-radius: 12px; padding: 7px 10px; background: #faf7f0; font-family: var(--mono); font-size: .82rem; }
    .empty { display: none; padding: 70px 0; color: var(--muted); font-family: var(--serif); font-size: 1.5rem; }
    #raw-view { display: none; }
    .raw-head { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 18px; }
    pre {
      margin: 0;
      overflow: auto;
      border-radius: 14px;
      padding: clamp(18px, 3vw, 34px);
      background: var(--code);
      color: var(--code-ink);
      font: 13px/1.68 var(--mono);
      tab-size: 2;
      box-shadow: 0 16px 45px rgba(10, 31, 24, .16);
      scrollbar-color: #769489 #10251f;
    }
    mark { background: #ffe89a; color: #28200a; }
    footer { padding: 28px clamp(20px, 5vw, 72px); border-top: 1px solid var(--line); color: var(--muted); font-size: .82rem; }
    .hidden { display: none !important; }
    @media (max-width: 850px) {
      .masthead-row { display: block; }
      .status { margin-top: 16px; }
      .ledger { grid-template-columns: repeat(2, 1fr); }
      .ledger div:nth-child(3) { border-left: 0; border-top: 1px solid var(--line); padding-left: 0; }
      .ledger div:nth-child(4) { border-top: 1px solid var(--line); }
      .toolbar { flex-wrap: wrap; }
      .search-wrap { max-width: none; order: 2; flex-basis: 100%; }
      .shell { display: block; }
      .sidebar { display: none; }
      main { padding-top: 34px; }
    }
    @media (max-width: 560px) {
      .field { grid-template-columns: 1fr; gap: 6px; }
      .return { text-align: left; }
      .copy-button { padding-inline: 9px; }
    }
    @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
  </style>
</head>
<body>
  <header class="masthead">
    <div class="masthead-row">
      <div>
        <h1>The complete Livia GraphQL schema.</h1>
        <p class="intro">Every root query, field, argument, object, enum, scalar, and deprecated compatibility path from both schema files. Search it as an API reference or open the exact merged SDL.</p>
      </div>
      <div class="status">Complete source schema</div>
    </div>
    <div class="ledger" aria-label="Schema totals">
      <div><strong>${query?.fields?.length || 0}</strong><span>Root queries</span></div>
      <div><strong>${objects.length}</strong><span>Object types</span></div>
      <div><strong>${fieldCount}</strong><span>Total fields</span></div>
      <div><strong>${deprecatedCount}</strong><span>Deprecated fields</span></div>
    </div>
  </header>

  <div class="toolbar" aria-label="Schema controls">
    <div class="view-switch" role="tablist" aria-label="Schema view">
      <button id="explorer-tab" role="tab" aria-selected="true" aria-controls="explorer-view">Browse</button>
      <button id="raw-tab" role="tab" aria-selected="false" aria-controls="raw-view">Full raw SDL</button>
    </div>
    <div class="search-wrap">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="11" cy="11" r="7"></circle><path d="m20 20-4-4"></path></svg>
      <input id="search" type="search" aria-label="Search schema" placeholder="Search query, type, field, or enum…" autocomplete="off">
      <button id="clear-search" aria-label="Clear search">×</button>
    </div>
    <button id="copy" class="copy-button">Copy full SDL</button>
  </div>

  <div class="shell">
    <nav class="sidebar" id="sidebar" aria-label="Schema definitions"></nav>
    <main>
      <div id="explorer-view" role="tabpanel" aria-labelledby="explorer-tab">
        <section id="root-api">
          <h2 class="section-heading">Root API</h2>
          <p class="section-note">These are all operations currently available under <code>Query</code>. Deprecated operations remain executable and are visibly marked.</p>
          <div id="query-definition"></div>
        </section>
        <section id="objects-section">
          <h2 class="section-heading">Object types</h2>
          <div id="objects"></div>
        </section>
        <section id="enums-section">
          <h2 class="section-heading">Enums</h2>
          <div id="enums"></div>
        </section>
        <section id="scalars-section">
          <h2 class="section-heading">Scalars</h2>
          <div id="scalars"></div>
        </section>
        <p id="empty" class="empty">No schema definitions match that search.</p>
      </div>
      <section id="raw-view" role="tabpanel" aria-labelledby="raw-tab">
        <div class="raw-head">
          <div>
            <h2 class="section-heading">Full raw SDL</h2>
            <p class="section-note">The exact contents of both source files, merged without omissions.</p>
          </div>
        </div>
        <pre><code id="raw-sdl"></code></pre>
      </section>
    </main>
  </div>
  <footer>Generated from <code>${sourceFiles.join('</code> and <code>')}</code>. Regenerate with <code>node docs/generate-graphql-schema-docs.cjs</code>.</footer>

  <script id="schema-data" type="application/json">${payload}</script>
  <script>
    const data = JSON.parse(document.getElementById('schema-data').textContent);
    const byName = new Set(data.definitions.map(definition => definition.name));
    const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[char]));
    const slug = name => 'type-' + name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    const typeText = type => type.kind === 'named' ? type.name : type.kind === 'list' ? '[' + typeText(type.ofType) + ']' : typeText(type.ofType) + '!';
    const typeHtml = type => {
      if (type.kind === 'named') {
        const name = escapeHtml(type.name);
        return byName.has(type.name) ? '<a class="type-link" href="#' + slug(type.name) + '">' + name + '</a>' : name;
      }
      return type.kind === 'list' ? '[' + typeHtml(type.ofType) + ']' : typeHtml(type.ofType) + '!';
    };
    const deprecatedHtml = reason => reason
      ? '<span class="deprecated">Deprecated</span><span class="reason">' + escapeHtml(reason) + '</span>'
      : '';
    const argsHtml = args => !args?.length ? '' : '<span class="args">(' + args.map(argument =>
      '<strong>' + escapeHtml(argument.name) + '</strong>: ' + typeHtml(argument.type) +
      (argument.defaultValue == null ? '' : ' = ' + escapeHtml(argument.defaultValue)) +
      (argument.deprecated ? ' <span class="deprecated">Deprecated</span>' : '')
    ).join(', ') + ')</span>';
    const definitionText = definition => [
      definition.name,
      ...(definition.fields || []).flatMap(field => [field.name, typeText(field.type), ...(field.arguments || []).map(argument => argument.name)]),
      ...(definition.values || []).map(value => value.name),
      ...(definition.members || []),
    ].join(' ').toLowerCase();
    const renderDefinition = definition => {
      const fields = (definition.fields || []).map(field =>
        '<div class="field" data-search="' + escapeHtml([field.name, typeText(field.type), ...(field.arguments || []).map(argument => argument.name)].join(' ').toLowerCase()) + '">' +
          '<dt><code class="field-name">' + escapeHtml(field.name) + '</code>' + argsHtml(field.arguments) + deprecatedHtml(field.deprecated) + '</dt>' +
          '<dd class="return"><code>' + typeHtml(field.type) + '</code></dd>' +
        '</div>'
      ).join('');
      const values = (definition.values || []).map(value =>
        '<li><span>' + escapeHtml(value.name) + '</span>' + deprecatedHtml(value.deprecated) + '</li>'
      ).join('');
      const members = (definition.members || []).map(member => '<li><a class="type-link" href="#' + slug(member) + '">' + escapeHtml(member) + '</a></li>').join('');
      return '<article class="definition" id="' + slug(definition.name) + '" data-definition-search="' + escapeHtml(definitionText(definition)) + '">' +
        '<div class="definition-head"><span class="kind">' + escapeHtml(definition.kind) + '</span><h3>' + escapeHtml(definition.name) + '</h3></div>' +
        (fields ? '<dl class="field-list">' + fields + '</dl>' : '') +
        (values || members ? '<ul class="enum-values">' + (values || members) + '</ul>' : '') +
      '</article>';
    };
    const render = () => {
      document.getElementById('query-definition').innerHTML = renderDefinition(data.query);
      document.getElementById('objects').innerHTML = data.objects.map(renderDefinition).join('');
      document.getElementById('enums').innerHTML = data.enums.map(renderDefinition).join('');
      document.getElementById('scalars').innerHTML = data.scalars.map(renderDefinition).join('');
      document.getElementById('raw-sdl').textContent = data.sdl;
      const groups = [
        ['Root', [data.query]],
        ['Objects', data.objects],
        ['Enums', data.enums],
        ['Scalars', data.scalars],
      ];
      document.getElementById('sidebar').innerHTML = groups.map(([name, definitions]) =>
        '<div class="nav-group"><h2>' + name + '</h2>' + definitions.map(definition =>
          '<a href="#' + slug(definition.name) + '" data-nav-for="' + slug(definition.name) + '">' + escapeHtml(definition.name) + '</a>'
        ).join('') + '</div>'
      ).join('');
    };
    render();

    const search = document.getElementById('search');
    const clear = document.getElementById('clear-search');
    const filter = () => {
      const term = search.value.trim().toLowerCase();
      clear.style.display = term ? 'block' : 'none';
      let visible = 0;
      document.querySelectorAll('.definition').forEach(definition => {
        const directMatch = definition.dataset.definitionSearch.includes(term);
        definition.classList.toggle('hidden', !directMatch);
        const nav = document.querySelector('[data-nav-for="' + definition.id + '"]');
        nav?.classList.toggle('hidden', !directMatch);
        if (directMatch) visible += 1;
      });
      document.getElementById('empty').style.display = visible ? 'none' : 'block';
      document.querySelectorAll('.nav-group').forEach(group => {
        group.classList.toggle('hidden', !group.querySelector('a:not(.hidden)'));
      });
    };
    search.addEventListener('input', filter);
    clear.addEventListener('click', () => { search.value = ''; filter(); search.focus(); });

    const explorerTab = document.getElementById('explorer-tab');
    const rawTab = document.getElementById('raw-tab');
    const setView = raw => {
      explorerTab.setAttribute('aria-selected', String(!raw));
      rawTab.setAttribute('aria-selected', String(raw));
      document.getElementById('explorer-view').style.display = raw ? 'none' : 'block';
      document.getElementById('raw-view').style.display = raw ? 'block' : 'none';
      document.getElementById('sidebar').style.visibility = raw ? 'hidden' : '';
      search.disabled = raw;
    };
    explorerTab.addEventListener('click', () => setView(false));
    rawTab.addEventListener('click', () => setView(true));
    document.getElementById('copy').addEventListener('click', async event => {
      try {
        if (navigator.clipboard?.writeText) {
          await navigator.clipboard.writeText(data.sdl);
        } else {
          const textarea = document.createElement('textarea');
          textarea.value = data.sdl;
          textarea.style.position = 'fixed';
          textarea.style.opacity = '0';
          document.body.appendChild(textarea);
          textarea.select();
          document.execCommand('copy');
          textarea.remove();
        }
        event.currentTarget.textContent = 'Copied';
      } catch {
        event.currentTarget.textContent = 'Copy unavailable';
      }
      setTimeout(() => { event.currentTarget.textContent = 'Copy full SDL'; }, 1600);
    });
  </script>
</body>
</html>`;

const output = path.join(root, 'docs/graphql-schema.html');
fs.writeFileSync(output, html);
console.log(`Wrote ${output}`);
console.log(`${query?.fields?.length || 0} root queries, ${definitions.length} definitions, ${fieldCount} fields, ${deprecatedCount} deprecated fields`);
