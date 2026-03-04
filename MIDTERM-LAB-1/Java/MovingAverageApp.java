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

    // Create a simple readable HTML report (table + summary)
    private static void writeHtmlReport(List<DataRecord> records, Path outPath) throws IOException {
    int totalRows = records.size();
    double sumSales = records.stream().mapToDouble(DataRecord::getTotalSales).sum();

    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'><title>Moving Average Report</title>");
    html.append("<style>body{font-family:Arial,Helvetica,sans-serif;padding:18px;color:#111}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:6px;text-align:left}th{background:#f2f2f2;cursor:pointer}th:hover{background:#e8e8e8}th.sorted-asc,th.sorted-desc{background:#e0e0e0}th.sorted-asc::after{content:' ▲';color:#333}th.sorted-desc::after{content:' ▼';color:#333}caption{font-weight:bold;margin-bottom:8px}</style>");
    // improved sort script: operate on tbody rows, toggle direction per‑column,
    // handle numeric and string values and avoid creating extra cells/columns.
    // also update header arrow indicator when sorted.
    html.append("<script>\n")
        .append("function sortTable(n){\n")
        .append("  const table = document.getElementById('dataTbl');\n")
        .append("  const tbody = table.tBodies[0];\n")
        .append("  if (!tbody) return;\n")
        .append("  // determine sort order: toggle if same column clicked repeatedly\n")
        .append("  const lastCol = table.getAttribute('data-sort-col');\n")
        .append("  let asc = table.getAttribute('data-sort-dir') !== 'asc' || lastCol !== String(n);\n")
        .append("  const rows = Array.from(tbody.querySelectorAll('tr'));\n")
        .append("  rows.sort((r1, r2) => {\n")
        .append("    let a = (r1.cells[n] ? r1.cells[n].textContent.trim() : '');\n")
        .append("    let b = (r2.cells[n] ? r2.cells[n].textContent.trim() : '');\n")
        .append("    const na = parseFloat(a.replace(/,/g, ''));\n")
        .append("    const nb = parseFloat(b.replace(/,/g, ''));\n")
        .append("    if (!isNaN(na) && !isNaN(nb)) {\n")
        .append("      return asc ? na - nb : nb - na;\n")
        .append("    }\n")
        .append("    return asc ? a.localeCompare(b) : b.localeCompare(a);\n")
        .append("  });\n")
        .append("  rows.forEach(r => tbody.appendChild(r));\n")
        .append("  table.setAttribute('data-sort-col', n);\n")
        .append("  table.setAttribute('data-sort-dir', asc ? 'asc' : 'desc');\n")
        .append("  updateIndicators(n, asc);\n")
        .append("}\n")
        .append("function updateIndicators(col, asc) {\n")
        .append("  const table = document.getElementById('dataTbl');\n")
        .append("  const headers = table.querySelectorAll('th');\n")
        .append("  headers.forEach((th, i) => {\n")
        .append("    th.classList.remove('sorted-asc','sorted-desc');\n")
        .append("    if (i === col) {\n")
        .append("      th.classList.add(asc ? 'sorted-asc' : 'sorted-desc');\n")
        .append("    }\n")
        .append("  });\n")
        .append("}\n")
        .append("</script>\n");

    html.append("</head><body>");
    html.append("<h1>Moving Average Report</h1>");
    html.append("<p>Click any column header to sort &nbsp;&#x25B2;/&#x25BC; the data</p>");
    html.append("<p>Total filtered rows: ").append(totalRows).append("</p>");
    html.append("<p>Total of total_sales (filtered): ").append(String.format("%.2f", sumSales)).append("</p>");
    html.append("<table id='dataTbl' data-sort-col='' data-sort-dir=''><thead><tr>");
    html.append("<th onclick='sortTable(0)'>Title</th>");
    html.append("<th onclick='sortTable(1)'>Release Date</th>");
    html.append("<th onclick='sortTable(2)'>Total Sales</th>");
    html.append("<th onclick='sortTable(3)'>3-Record Moving Avg</th>");
    html.append("</tr></thead><tbody>");

    for (DataRecord r : records) {
        html.append("<tr>");
        html.append("<td>").append(escapeHtml(r.getTitle())).append("</td>");
        html.append("<td>").append(escapeHtml(r.getReleaseDateRaw())).append("</td>");
        html.append("<td>").append(String.format("%.2f", r.getTotalSales())).append("</td>");
        html.append("<td>").append(r.getMovingAvg()).append("</td>");
        html.append("</tr>");
    }

    html.append("</tbody></table>");
    html.append("<p>Generated by MovingAverageApp</p>");
    html.append("</body></html>");
    Files.write(outPath, html.toString().getBytes("UTF-8"));
}

    // simple HTML escape to avoid broken markup
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}