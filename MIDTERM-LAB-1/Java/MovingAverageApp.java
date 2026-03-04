// MovingAverageApp.java
import java.io.*;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class MovingAverageApp {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        File csvFile;

        // 1) Prompt until valid path
        while (true) {
            System.out.print("Enter dataset file path: ");
            String inputPath = scanner.nextLine().trim();
            csvFile = new File(inputPath);
            if (!csvFile.exists()) {
                System.out.println("Invalid: file does not exist. Try again.");
                continue;
            }
            if (!csvFile.isFile()) {
                System.out.println("Invalid: path is not a file. Try again.");
                continue;
            }
            if (!csvFile.canRead()) {
                System.out.println("Invalid: file is not readable. Check permissions. Try again.");
                continue;
            }
            String name = csvFile.getName().toLowerCase();
            if (!name.endsWith(".csv")) {
                System.out.println("Invalid: file does not have .csv extension. Try again.");
                continue;
            }
            break;
        }

        try {
            List<DataRecord> records = readCsv(csvFile);
            if (records.isEmpty()) {
                System.out.println("No valid records found in CSV. Exiting.");
                return;
            }

            // Sort by release date (nulls last)
            records.sort(Comparator.comparing(DataRecord::getReleaseDate, Comparator.nullsLast(Comparator.naturalOrder())));

            // Compute moving average
            computeMovingAverage(records);

            // Filter out irrelevant rows:
            // Keep rows where totalSales > 0 OR (movingAvg != "N/A" and > 0)
            List<DataRecord> filtered = records.stream()
                .filter(r -> r.getTotalSales() > 0.0 ||
                             ( !"N/A".equals(r.getMovingAvg()) && parseDoubleSafe(r.getMovingAvg()) > 0.0 ))
                .collect(Collectors.toList());

            // Print a short console summary
            System.out.println("\n--- Data Summary ---");
            System.out.println("Total rows parsed: " + records.size());
            System.out.println("Rows with relevant values (filtered): " + filtered.size());
            System.out.println("Writing output files...");

            // Write CSV and HTML UI report
            Path csvOut = Paths.get("moving_average_output.csv");
            writeOutputCsv(filtered, csvOut);

            Path htmlOut = Paths.get("moving_average_report.html");
            writeHtmlReport(filtered, htmlOut);

            System.out.println("CSV written to: " + csvOut.toAbsolutePath());
            System.out.println("HTML report written to: " + htmlOut.toAbsolutePath());

            // Try to open HTML report in default browser (best-effort)
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(htmlOut.toUri());
                } else {
                    System.out.println("Desktop.browse not supported on this environment. Open the HTML file manually.");
                }
            } catch (Exception ex) {
                System.out.println("Could not open browser automatically: " + ex.getMessage());
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            scanner.close();
        }
    }

    // Read CSV and return DataRecord list. Supports quoted fields.
    private static List<DataRecord> readCsv(File file) throws IOException {
        List<DataRecord> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String headerLine = br.readLine();
            if (headerLine == null) throw new IOException("CSV is empty.");
            List<String> headers = parseCsvLine(headerLine);
            int idxTitle = findHeader(headers, "title");
            int idxRelease = findHeader(headers, "release_date");
            int idxTotal = findHeader(headers, "total_sales");
            if (idxTitle == -1 || idxRelease == -1 || idxTotal == -1) {
                throw new IOException("CSV missing required headers: title, release_date, total_sales");
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> fields = parseCsvLine(line);
                int maxIdx = Math.max(Math.max(idxTitle, idxRelease), idxTotal);
                if (fields.size() <= maxIdx) continue;
                String title = fields.get(idxTitle).trim();
                String releaseRaw = fields.get(idxRelease).trim();
                String totalRaw = fields.get(idxTotal).trim();
                double total = 0.0;
                try { total = Double.parseDouble(totalRaw); } catch (NumberFormatException nfe) { total = 0.0; }
                LocalDate releaseDate = parseReleaseDate(releaseRaw); // may return null
                list.add(new DataRecord(title, releaseRaw, releaseDate, total));
            }
        }
        return list;
    }

    // Basic CSV parser supporting quotes and escaped quotes ("")
    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    private static int findHeader(List<String> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).trim().equalsIgnoreCase(name)) return i;
            if (headers.get(i).toLowerCase().contains(name.toLowerCase())) return i;
        }
        return -1;
    }

    private static LocalDate parseReleaseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        try {
            if (s.contains("/")) {
                String[] p = s.split("/");
                if (p.length == 3) {
                    int m = Integer.parseInt(p[0].trim());
                    int d = Integer.parseInt(p[1].trim());
                    int y = Integer.parseInt(p[2].trim());
                    return LocalDate.of(y, m, d);
                }
            }
            return LocalDate.parse(s); // ISO fallback
        } catch (Exception e) {
            return null;
        }
    }

    private static void computeMovingAverage(List<DataRecord> records) {
        for (int i = 0; i < records.size(); i++) {
            if (i < 2) {
                records.get(i).setMovingAvg("N/A");
            } else {
                double sum = records.get(i).getTotalSales()
                           + records.get(i-1).getTotalSales()
                           + records.get(i-2).getTotalSales();
                records.get(i).setMovingAvg(String.format("%.2f", sum / 3.0));
            }
        }
    }

    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    private static void writeOutputCsv(List<DataRecord> records, Path outPath) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outPath)) {
            bw.write("Title,Release Date,Total Sales,3-Record Moving Avg");
            bw.newLine();
            for (DataRecord r : records) {
                String titleEsc = "\"" + r.getTitle().replace("\"", "\"\"") + "\"";
                bw.write(String.join(",", titleEsc, r.getReleaseDateRaw(), String.valueOf(r.getTotalSales()), r.getMovingAvg()));
                bw.newLine();
            }
        }
    }

    // Create HTML report with pagination support
    private static void writeHtmlReport(List<DataRecord> records, Path outPath) throws IOException {
        int totalRows = records.size();
        double sumSales = records.stream().mapToDouble(DataRecord::getTotalSales).sum();
        final int itemsPerPage = 50;

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset='utf-8'><title>Moving Average Report</title>");
        html.append("<style>")
            .append("body{font-family:Arial,sans-serif;padding:18px;color:#111}")
            .append("table{border-collapse:collapse;width:100%;margin:12px 0}")
            .append("th,td{border:1px solid #ddd;padding:8px;text-align:left}")
            .append("th{background:#f2f2f2;cursor:pointer}")
            .append("th:hover{background:#e8e8e8}")
            .append("th.sorted-asc::after,th.sorted-desc::after{margin-left:4px}")
            .append("th.sorted-asc::after{content:' ▲'}")
            .append("th.sorted-desc::after{content:' ▼'}")
            .append("#paginationWrap{padding:12px;background:#f9fafb;border-radius:6px;border:1px solid #e5e7eb;margin:16px 0;text-align:center}")
            .append("button{padding:6px 12px;margin:0 4px;border:none;background:#2563eb;color:white;border-radius:4px;cursor:pointer}")
            .append("button:hover{background:#1d4ed8}")
            .append("input[type=number]{border:1px solid #d1d5db;border-radius:4px;padding:4px;width:60px}")
            .append("</style>");

        html.append("<script>\n");
        html.append("const ITEMS_PER_PAGE = ").append(itemsPerPage).append(";\n");
        html.append("let currentPage = 1;\n");
        html.append("let allRecords = ").append(buildJsonArray(records)).append(";\n");
        
        html.append("function renderPage() {\n")
            .append("  const totalPages = Math.ceil(allRecords.length / ITEMS_PER_PAGE);\n")
            .append("  const startIdx = (currentPage - 1) * ITEMS_PER_PAGE;\n")
            .append("  const endIdx = Math.min(startIdx + ITEMS_PER_PAGE, allRecords.length);\n")
            .append("  const tbody = document.getElementById('dataTbl').tBodies[0];\n")
            .append("  tbody.innerHTML = '';\n")
            .append("  for (let i = startIdx; i < endIdx; i++) {\n")
            .append("    const r = allRecords[i];\n")
            .append("    const tr = document.createElement('tr');\n")
            .append("    tr.innerHTML = '<td>' + r.title + '</td><td>' + r.date + '</td><td>' + r.sales.toFixed(2) + '</td><td>' + r.movAvg + '</td>';\n")
            .append("    tbody.appendChild(tr);\n")
            .append("  }\n")
            .append("  updatePaginationUI(totalPages);\n")
            .append("}\n");

        html.append("function updatePaginationUI(totalPages) {\n")
            .append("  const wrap = document.getElementById('paginationWrap');\n")
            .append("  if (totalPages <= 1) { wrap.style.display = 'none'; return; }\n")
            .append("  wrap.style.display = 'block';\n")
            .append("  let html = '';\n")
            .append("  if (currentPage > 1) html += '<button onclick=\"goPage(1)\">« First</button> <button onclick=\"goPage(' + (currentPage-1) + ')\">← Prev</button> ';\n")
            .append("  html += '<span>Page <input type=\"number\" min=\"1\" max=\"' + totalPages + '\" value=\"' + currentPage + '\" id=\"pageNum\" onchange=\"goPage(parseInt(this.value))\"> of ' + totalPages + ' (' + allRecords.length + ' records)</span> ';\n")
            .append("  if (currentPage < totalPages) html += '<button onclick=\"goPage(' + (currentPage+1) + ')\">Next →</button> <button onclick=\"goPage(' + totalPages + ')\">Last »</button>';\n")
            .append("  wrap.innerHTML = html;\n")
            .append("}\n");

        html.append("function goPage(n) {\n")
            .append("  const totalPages = Math.ceil(allRecords.length / ITEMS_PER_PAGE);\n")
            .append("  if (n < 1 || n > totalPages) return;\n")
            .append("  currentPage = n;\n")
            .append("  renderPage();\n")
            .append("}\n");

        html.append("function sortTable(colIdx) {\n")
            .append("  const asc = !document.getElementById('dataTbl').dataset.ascending || document.getElementById('dataTbl').dataset.sortCol !== String(colIdx);\n")
            .append("  allRecords.sort((a, b) => {\n")
            .append("    let aVal = colIdx === 0 ? a.title : colIdx === 1 ? a.date : colIdx === 2 ? a.sales : parseFloat(a.movAvg);\n")
            .append("    let bVal = colIdx === 0 ? b.title : colIdx === 1 ? b.date : colIdx === 2 ? b.sales : parseFloat(b.movAvg);\n")
            .append("    if (typeof aVal === 'number' && typeof bVal === 'number') return asc ? aVal - bVal : bVal - aVal;\n")
            .append("    return asc ? String(aVal).localeCompare(String(bVal)) : String(bVal).localeCompare(String(aVal));\n")
            .append("  });\n")
            .append("  document.getElementById('dataTbl').dataset.sortCol = colIdx;\n")
            .append("  document.getElementById('dataTbl').dataset.ascending = asc;\n")
            .append("  currentPage = 1;\n")
            .append("  renderPage();\n")
            .append("  updateSortIndicators(colIdx, asc);\n")
            .append("}\n");

        html.append("function updateSortIndicators(col, asc) {\n")
            .append("  document.querySelectorAll('th').forEach((th, i) => {\n")
            .append("    th.classList.remove('sorted-asc', 'sorted-desc');\n")
            .append("    if (i === col) th.classList.add(asc ? 'sorted-asc' : 'sorted-desc');\n")
            .append("  });\n")
            .append("}\n");

        html.append("window.addEventListener('DOMContentLoaded', renderPage);\n");
        html.append("</script>\n");

        html.append("</head><body><h1>Moving Average Report</h1>");
        html.append("<p>Click column headers to sort. ");
        html.append("Total rows: ").append(totalRows).append("; ");
        html.append("Total sales: ").append(String.format("%.2f", sumSales)).append("</p>");
        html.append("<table id='dataTbl' data-sort-col='0' data-ascending='false'>");
        html.append("<thead><tr>");
        html.append("<th onclick='sortTable(0)'>Title</th>");
        html.append("<th onclick='sortTable(1)'>Release Date</th>");
        html.append("<th onclick='sortTable(2)'>Total Sales</th>");
        html.append("<th onclick='sortTable(3)'>3-Record Moving Avg</th>");
        html.append("</tr></thead><tbody></tbody></table>");
        html.append("<div id='paginationWrap' style='display:none;'></div>");
        html.append("<p><small>Generated by MovingAverageApp</small></p>");
        html.append("</body></html>");

        Files.write(outPath, html.toString().getBytes("UTF-8"));
    }

    // Convert records to JSON array for client-side pagination
    private static String buildJsonArray(List<DataRecord> records) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < records.size(); i++) {
            DataRecord r = records.get(i);
            if (i > 0) json.append(",");
            json.append("{\"title\":\"").append(jsonEscape(r.getTitle()))
                .append("\",\"date\":\"").append(jsonEscape(r.getReleaseDateRaw()))
                .append("\",\"sales\":").append(r.getTotalSales())
                .append(",\"movAvg\":\"").append(jsonEscape(r.getMovingAvg()))
                .append("\"}");
        }
        json.append("]");
        return json.toString();
    }

    // Escape special characters for JSON
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // simple HTML escape to avoid broken markup
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}