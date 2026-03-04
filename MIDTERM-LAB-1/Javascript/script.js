// script.js - browser-only
document.getElementById('processBtn').addEventListener('click', processFile);
const fileInput = document.getElementById('fileInput');
const statusEl = document.getElementById('status');
const tableWrap = document.getElementById('tableWrap');
const summaryEl = document.getElementById('summary');
const hideZerosCheckbox = document.getElementById('hideZeros');
const controls = document.getElementById('controls');
const downloadCsvBtn = document.getElementById('downloadCsv');
const downloadHtmlBtn = document.getElementById('downloadHtml');
const searchBox = document.getElementById('searchBox');
const sortBySelect = document.getElementById('sortBySelect');
const sortDescCheckbox = document.getElementById('sortDesc');

// add visual sort indicator styles once
(function(){
  const s=document.createElement('style');
  s.id='sort-style';
  s.textContent=`th{cursor:pointer} th:hover{background:#e8e8e8} th.sorted-asc,th.sorted-desc{background:#e0e0e0} th.sorted-asc::after{content:' ▲';color:#333} th.sorted-desc::after{content:' ▼';color:#333}`;
  document.head.appendChild(s);
})();

let lastFilteredRecords = [];

function readFileAsText(file) {
  return new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => res(fr.result);
    fr.onerror = () => rej(fr.error);
    fr.readAsText(file);
  });
}

function parseCsvLine(line) {
  const fields = [];
  let cur = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      if (inQuotes && i + 1 < line.length && line[i+1] === '"') { cur += '"'; i++; }
      else inQuotes = !inQuotes;
    } else if (ch === ',' && !inQuotes) {
      fields.push(cur);
      cur = '';
    } else cur += ch;
  }
  fields.push(cur);
  return fields;
}

function findHeaderIndex(headers, name) {
  name = name.toLowerCase();
  for (let i = 0; i < headers.length; i++) {
    const h = headers[i].trim().toLowerCase();
    if (h === name || h.includes(name)) return i;
  }
  return -1;
}

function parseDate(raw) {
  if (!raw) return null;
  raw = raw.trim();
  if (raw.includes('/')) {
    const p = raw.split('/');
    if (p.length === 3) {
      const mm = parseInt(p[0],10), dd = parseInt(p[1],10), yy = parseInt(p[2],10);
      if (!isNaN(mm) && !isNaN(dd) && !isNaN(yy)) return new Date(yy, mm-1, dd);
    }
  }
  const d = new Date(raw);
  return isNaN(d) ? null : d;
}

function computeMovingAvg(records) {
  for (let i = 0; i < records.length; i++) {
    if (i < 2) records[i].movingAvg = 'N/A';
    else records[i].movingAvg = ((records[i].totalSales + records[i-1].totalSales + records[i-2].totalSales)/3).toFixed(2);
  }
}

function filterRelevant(records) {
  return records.filter(r => {
    if (r.totalSales > 0) return true;
    if (r.movingAvg !== 'N/A' && parseFloat(r.movingAvg) > 0) return true;
    return false;
  });
}

function buildTable(records) {
  if (!records || records.length === 0) {
    tableWrap.innerHTML = "<p>No relevant rows to display.</p>";
    return;
  }
  // user instruction
  tableWrap.innerHTML = '<p>Click any column header to sort ▲/▼</p>';
  let html = '<table id="dataTbl" data-sort-col="" data-sort-dir=""><thead><tr>';
  const headers = ['Title','Release Date','Total Sales','3-Record Moving Avg'];
  headers.forEach((h, idx) => {
    html += `<th onclick="sortTable(${idx})">${h}</th>`;
  });
  html += '</tr></thead><tbody>';

  for (const r of records) {
    html += `<tr><td>${escapeHtml(r.title)}</td><td>${escapeHtml(r.releaseRaw)}</td><td>${r.totalSales.toFixed(2)}</td><td>${r.movingAvg}</td></tr>`;
  }
  html += '</tbody></table>';
  tableWrap.innerHTML = html;

  // sorting script using tbody and indicator classes
  // n = column index, optional asc flag toggles direction if provided
  window.sortTable = function(n, forceAsc){
    console.log('sortTable called, column', n, 'forceAsc', forceAsc);
    const table = document.getElementById('dataTbl');
    const tbody = table.tBodies[0];
    if (!tbody) return;
    const lastCol = table.getAttribute('data-sort-col');
    let asc;
    if (typeof forceAsc === 'boolean') {
      asc = forceAsc;
    } else {
      asc = table.getAttribute('data-sort-dir') !== 'asc' || lastCol !== String(n);
    }
    const rows = Array.from(tbody.querySelectorAll('tr'));
    rows.sort((r1,r2)=>{
      let a = (r1.cells[n] ? r1.cells[n].textContent.trim() : '');
      let b = (r2.cells[n] ? r2.cells[n].textContent.trim() : '');
      const na = parseFloat(a.replace(/,/g,''));
      const nb = parseFloat(b.replace(/,/g,''));
      if(!isNaN(na) && !isNaN(nb)){
        return asc ? na - nb : nb - na;
      }
      return asc ? a.localeCompare(b) : b.localeCompare(a);
    });
    rows.forEach(r=>tbody.appendChild(r));
    table.setAttribute('data-sort-col', n);
    table.setAttribute('data-sort-dir', asc?'asc':'desc');
    updateIndicators(n, asc);
  }
}

// globally-available helper used by the sorter to update header arrows
function updateIndicators(col, asc) {
  const table = document.getElementById('dataTbl');
  if (!table) return;
  const headers = table.querySelectorAll('th');
  headers.forEach((th, i) => {
    th.classList.remove('sorted-asc','sorted-desc');
    if (i === col) {
      th.classList.add(asc ? 'sorted-asc' : 'sorted-desc');
    }
  });
}

function escapeHtml(s) {
  if (!s) return '';
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

async function processFile() {
  tableWrap.innerHTML = '';
  summaryEl.innerHTML = '';
  controls.style.display = 'none';
  statusEl.textContent = '';

  const file = fileInput.files[0];
  if (!file) { statusEl.textContent = 'Please choose a CSV file.'; return; }
  statusEl.textContent = 'Reading file...';

  try {
    const text = await readFileAsText(file);
    const lines = text.split(/\r?\n/).filter(l => l.trim() !== '');
    if (lines.length < 2) { statusEl.textContent = 'CSV appears empty or has no data rows.'; return; }

    const headerFields = parseCsvLine(lines[0]).map(h => h.trim());
    const iTitle = findHeaderIndex(headerFields, 'title');
    const iRelease = findHeaderIndex(headerFields, 'release_date');
    const iTotal = findHeaderIndex(headerFields, 'total_sales');
    if (iTitle === -1 || iRelease === -1 || iTotal === -1) { statusEl.textContent = 'CSV missing required headers.'; return; }

    // parse rows
    const records = [];
    for (let i = 1; i < lines.length; i++) {
      const row = parseCsvLine(lines[i]);
      if (row.length <= Math.max(Math.max(iTitle, iRelease), iTotal)) continue;
      const title = row[iTitle] || '';
      const releaseRaw = row[iRelease] || '';
      const total = parseFloat((row[iTotal]||'').trim()) || 0.0;
      records.push({ title: title.trim(), releaseRaw: releaseRaw.trim(), totalSales: total, releaseDateObj: parseDate(releaseRaw) });
    }

    // sort by date (nulls last)
    records.sort((a,b) => {
      if (!a.releaseDateObj && !b.releaseDateObj) return 0;
      if (!a.releaseDateObj) return 1;
      if (!b.releaseDateObj) return -1;
      return a.releaseDateObj - b.releaseDateObj;
    });

    computeMovingAvg(records);

    // filter relevant
    let filtered = filterRelevant(records);
    lastFilteredRecords = filtered;

    statusEl.textContent = 'Done. Found ' + filtered.length + ' relevant rows.';
    summaryEl.innerHTML = `<p>Total parsed rows: ${records.length}. Relevant rows: ${filtered.length}. Total sales (filtered): ${filtered.reduce((s,r)=> s + r.totalSales,0).toFixed(2)}</p>`;

    // show controls
    controls.style.display = 'block';
    buildTable(filtered);
    // apply initial sort if user requested
    if (sortBySelect) {
      const col = parseInt(sortBySelect.value,10);
      const desc = sortDescCheckbox && sortDescCheckbox.checked;
      if (!isNaN(col)) {
        // call with forced direction
        sortTable(col, !desc);
      }
    }

    // hook up search and downloads
    searchBox.oninput = () => {
      const q = searchBox.value.trim().toLowerCase();
      const sub = filtered.filter(r => r.title.toLowerCase().includes(q));
      buildTable(sub);
    };

    hideZerosCheckbox.onchange = () => {
      let current = filtered;
      if (hideZerosCheckbox.checked) current = filtered;
      else current = records; // show all parsed rows (including zeros)
      buildTable(current);
    };

    downloadCsvBtn.onclick = () => {
      const csv = buildCsvFromRecords(filtered);
      downloadBlob(csv, 'moving_average_output.csv', 'text/csv');
    };
    downloadHtmlBtn.onclick = () => {
      const html = buildHtmlReport(filtered);
      downloadBlob(html, 'moving_average_report.html', 'text/html');
    };

  } catch (err) {
    statusEl.textContent = 'Error: ' + (err.message || err);
  }
}

function buildCsvFromRecords(records) {
  const lines = ['Title,Release Date,Total Sales,3-Record Moving Avg'];
  for (const r of records) {
    const title = '"' + String(r.title).replace(/"/g,'""') + '"';
    lines.push([title, r.releaseRaw, r.totalSales.toFixed(2), r.movingAvg].join(','));
  }
  return lines.join('\n');
}

function buildHtmlReport(records) {
  const total = records.length;
  const sum = records.reduce((s,r)=> s + r.totalSales, 0).toFixed(2);
  let html = `<!doctype html><html><head><meta charset='utf-8'><title>Moving Average Report</title><style>body{font-family:Arial;padding:14px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:6px}th{cursor:pointer}th:hover{background:#e8e8e8}th.sorted-asc,th.sorted-desc{background:#e0e0e0}th.sorted-asc::after{content:' ▲';color:#333}th.sorted-desc::after{content:' ▼';color:#333}</style><script>\nfunction sortTable(n){\n  const table=document.getElementById('dataTbl');\n  const tbody=table.tBodies[0]; if(!tbody) return;\n  const lastCol=table.getAttribute('data-sort-col');\n  let asc=table.getAttribute('data-sort-dir')!=='asc' || lastCol!==String(n);\n  const rows=Array.from(tbody.querySelectorAll('tr'));
  rows.sort((r1,r2)=>{let a=(r1.cells[n]?r1.cells[n].textContent.trim():'');let b=(r2.cells[n]?r2.cells[n].textContent.trim():'');const na=parseFloat(a.replace(/,/g,''));const nb=parseFloat(b.replace(/,/g,''));if(!isNaN(na)&&!isNaN(nb)){return asc?na-nb:nb-na;}return asc?a.localeCompare(b):b.localeCompare(a);});
  rows.forEach(r=>tbody.appendChild(r));
  table.setAttribute('data-sort-col',n);table.setAttribute('data-sort-dir',asc?'asc':'desc');
  updateIndicators(n,asc);
}\nfunction updateIndicators(col,asc){const table=document.getElementById('dataTbl');const headers=table.querySelectorAll('th');headers.forEach((th,i)=>{th.classList.remove('sorted-asc','sorted-desc');if(i===col){th.classList.add(asc?'sorted-asc':'sorted-desc');}});}\n</script></head><body>`;
  html += `<h1>Moving Average Report</h1><p>Click any column header to sort &#x25B2;/&#x25BC;</p><p>Total rows: ${total}; total_sales (sum): ${sum}</p>`;
  html += `<table id="dataTbl" data-sort-col="" data-sort-dir=""><thead><tr><th>Title</th><th>Release Date</th><th>Total Sales</th><th>3-Record Moving Avg</th></tr></thead><tbody>`;
  for (const r of records) {
    html += `<tr><td>${escapeHtml(r.title)}</td><td>${escapeHtml(r.releaseRaw)}</td><td>${r.totalSales.toFixed(2)}</td><td>${r.movingAvg}</td></tr>`;
  }
  html += `</tbody></table></body></html>`;
  return html;
}

function downloadBlob(content, filename, mime) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}