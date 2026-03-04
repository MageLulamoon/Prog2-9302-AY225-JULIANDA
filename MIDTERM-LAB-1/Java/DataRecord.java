// DataRecord.java
import java.time.LocalDate;

public class DataRecord {
    private final String title;
    private final String releaseDateRaw;
    private final LocalDate releaseDate;
    private final double totalSales;
    private String movingAvg; // "N/A" or formatted number as string

    public DataRecord(String title, String releaseDateRaw, LocalDate releaseDate, double totalSales) {
        this.title = title == null ? "" : title;
        this.releaseDateRaw = releaseDateRaw == null ? "" : releaseDateRaw;
        this.releaseDate = releaseDate;
        this.totalSales = totalSales;
        this.movingAvg = "N/A";
    }

    public String getTitle() { return title; }
    public String getReleaseDateRaw() { return releaseDateRaw; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public double getTotalSales() { return totalSales; }
    public String getMovingAvg() { return movingAvg; }
    public void setMovingAvg(String movingAvg) { this.movingAvg = movingAvg; }
}