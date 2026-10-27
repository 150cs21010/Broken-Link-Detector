/*
 * ============================================================================
 *  Develop a Broken Link Detector for a Website Using Selenium
 *  Author : Kiran Chakrasali (3BR24CS409)
 *  Guide  : Dr. Chidananda H, Dept. of CSE, BITM Ballari
 * ============================================================================
 *
 *  WHAT IT DOES
 *    1. Opens a web page in Chrome using Selenium WebDriver.
 *    2. Collects every <a href="..."> link (skips mailto:, tel:, javascript:,
 *       "#" anchors and duplicates).
 *    3. Sends an HTTP HEAD request (falls back to GET) to every link.
 *    4. Classifies each link as OK / REDIRECT / BROKEN / ERROR.
 *    5. Writes reports/broken-links.csv and reports/broken-links.html.
 *    6. Exits with code 1 if broken links are found, otherwise 0.
 *
 *  REQUIREMENTS
 *    JDK 17+, Maven, Google Chrome. Selenium Manager downloads the driver.
 *
 *  HOW TO RUN (Maven)
 *    Folder layout:
 *        BrokenLinkDetector/
 *          |-- pom.xml                      (see snippet below)
 *          |-- src/main/java/BrokenLinkDetector.java   (this file)
 *
 *    pom.xml essentials:
 *        <properties>
 *          <maven.compiler.source>17</maven.compiler.source>
 *          <maven.compiler.target>17</maven.compiler.target>
 *        </properties>
 *        <dependencies>
 *          <dependency>
 *            <groupId>org.seleniumhq.selenium</groupId>
 *            <artifactId>selenium-java</artifactId>
 *            <version>4.27.0</version>
 *          </dependency>
 *        </dependencies>
 *        <build><plugins><plugin>
 *          <groupId>org.codehaus.mojo</groupId>
 *          <artifactId>exec-maven-plugin</artifactId>
 *          <version>3.1.0</version>
 *          <configuration><mainClass>BrokenLinkDetector</mainClass></configuration>
 *        </plugin></plugins></build>
 *
 *    Run:
 *        mvn compile exec:java -Dexec.args="https://example.com"
 *        mvn compile exec:java -Dexec.args="https://example.com --headless"
 *
 *    With no URL it scans the local demo page:
 *        http://localhost:8080/demo-links.html
 *    (start it with:  python -m http.server 8080  inside the demo folder)
 *
 *  NOTE: Scan only websites you are permitted to test.
 * ============================================================================
 */

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class BrokenLinkDetector {

    private static final String DEFAULT_URL = "http://localhost:8080/demo-links.html";
    private static final String REPORT_DIR = "reports";

    // ------------------------------------------------------------------
    //  Data model
    // ------------------------------------------------------------------
    static class LinkResult {
        private final String sourcePage;
        private final String url;
        private final int statusCode;
        private final String status;   // OK, REDIRECT, BROKEN, ERROR
        private final String message;

        LinkResult(String sourcePage, String url, int statusCode,
                   String status, String message) {
            this.sourcePage = sourcePage;
            this.url = url;
            this.statusCode = statusCode;
            this.status = status;
            this.message = message;
        }

        String getSourcePage() { return sourcePage; }
        String getUrl()        { return url; }
        int getStatusCode()    { return statusCode; }
        String getStatus()     { return status; }
        String getMessage()    { return message; }
        boolean isBroken()     { return "BROKEN".equals(status) || "ERROR".equals(status); }
    }

    // ------------------------------------------------------------------
    //  Page object: opens the page and collects links with Selenium
    // ------------------------------------------------------------------
    static class LinkPage {
        private final WebDriver driver;
        private final WebDriverWait wait;
        private final By anchors = By.tagName("a");

        LinkPage(WebDriver driver) {
            this.driver = driver;
            this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        }

        void open(String url) {
            driver.get(url);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
        }

        String getTitle() { return driver.getTitle(); }

        Set<String> collectLinks() {
            Set<String> links = new LinkedHashSet<>();
            List<WebElement> elements = driver.findElements(anchors);
            for (WebElement a : elements) {
                String raw = a.getDomAttribute("href");          // as written in HTML
                if (raw == null || raw.trim().startsWith("#")) continue; // in-page anchor
                String href = a.getAttribute("href");            // resolved absolute URL
                if (isCheckable(href)) {
                    links.add(href.trim());
                }
            }
            return links;
        }

        private boolean isCheckable(String href) {
            if (href == null || href.isBlank()) return false;
            String h = href.trim().toLowerCase();
            return h.startsWith("http://") || h.startsWith("https://");
        }
    }

    // ------------------------------------------------------------------
    //  Link validation using HTTP status codes
    // ------------------------------------------------------------------
    static class LinkChecker {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        LinkResult check(String sourcePage, String url) {
            try {
                int code = send(url, "HEAD");
                if (code == 405 || code == 501 || code == 403) {
                    code = send(url, "GET");   // some servers reject HEAD
                }
                return classify(sourcePage, url, code);
            } catch (Exception e) {
                return new LinkResult(sourcePage, url, -1, "ERROR",
                        e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "Server unreachable" : e.getMessage()));
            }
        }

        private int send(String url, String method) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "BrokenLinkDetector/1.0")
                    .build();
            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        }

        private LinkResult classify(String src, String url, int code) {
            if (code >= 200 && code < 300) {
                return new LinkResult(src, url, code, "OK", "Link is working");
            } else if (code >= 300 && code < 400) {
                return new LinkResult(src, url, code, "REDIRECT", "Link redirects");
            } else if (code >= 400) {
                return new LinkResult(src, url, code, "BROKEN", "Client/Server error " + code);
            }
            return new LinkResult(src, url, code, "ERROR", "Unexpected response");
        }
    }

    // ------------------------------------------------------------------
    //  CSV and HTML report writer
    // ------------------------------------------------------------------
    static class ReportWriter {
        static void writeCsv(String path, List<LinkResult> results) throws IOException {
            new File(path).getAbsoluteFile().getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
                pw.println("SourcePage,URL,StatusCode,Status,Message");
                for (LinkResult r : results) {
                    pw.printf("\"%s\",\"%s\",%d,%s,\"%s\"%n", r.getSourcePage(),
                            r.getUrl(), r.getStatusCode(), r.getStatus(), r.getMessage());
                }
            }
        }

        static void writeHtml(String path, List<LinkResult> results) throws IOException {
            new File(path).getAbsoluteFile().getParentFile().mkdirs();
            long broken = results.stream().filter(LinkResult::isBroken).count();
            try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
                pw.println("<html><head><title>Broken Link Report</title></head><body>");
                pw.println("<h2>Broken Link Report</h2>");
                pw.println("<p>Total: " + results.size() + " | Broken: " + broken + "</p>");
                pw.println("<table border='1' cellpadding='5'>");
                pw.println("<tr><th>Source</th><th>URL</th><th>Code</th><th>Status</th></tr>");
                for (LinkResult r : results) {
                    String color = r.isBroken() ? "#f8d7da" : "#d4edda";
                    pw.printf("<tr style='background:%s'><td>%s</td><td>%s</td>"
                            + "<td>%d</td><td>%s</td></tr>%n", color,
                            r.getSourcePage(), r.getUrl(), r.getStatusCode(), r.getStatus());
                }
                pw.println("</table></body></html>");
            }
        }
    }

    // ------------------------------------------------------------------
    //  Main: ties everything together
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        String baseUrl = DEFAULT_URL;
        boolean headless = false;
        for (String arg : args) {
            if (arg.equals("--headless")) headless = true;
            else baseUrl = arg;
        }

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");
        if (headless) options.addArguments("--headless=new");

        WebDriver driver = new ChromeDriver(options);   // Selenium Manager handles the driver
        List<LinkResult> results = new ArrayList<>();
        try {
            LinkPage page = new LinkPage(driver);
            page.open(baseUrl);
            System.out.println("Page title : " + page.getTitle());

            Set<String> links = page.collectLinks();
            System.out.println("Links found: " + links.size());
            if (links.isEmpty()) {
                System.out.println("No checkable links on this page.");
                return;
            }

            LinkChecker checker = new LinkChecker();
            for (String link : links) {
                LinkResult r = checker.check(baseUrl, link);
                results.add(r);
                System.out.println(r.getStatus() + " [" + r.getStatusCode() + "] " + link);
            }
        } finally {
            driver.quit();
        }

        ReportWriter.writeCsv(REPORT_DIR + "/broken-links.csv", results);
        ReportWriter.writeHtml(REPORT_DIR + "/broken-links.html", results);

        long broken = results.stream().filter(LinkResult::isBroken).count();
        System.out.println("Total: " + results.size() + " | Broken: " + broken);
        System.out.println("Reports written to " + REPORT_DIR + "/");
        System.exit(broken == 0 ? 0 : 1);
    }
}
