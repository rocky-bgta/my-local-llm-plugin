package plugin.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class TestReportUtil {

    public record TestResult(String name, String status) {}

    public static List<TestResult> parseReports(String projectPath) {
        List<TestResult> results = new ArrayList<>();
        File reportsDir = new File(projectPath, "target/surefire-reports");
        if (!reportsDir.exists() || !reportsDir.isDirectory()) {
            return results;
        }

        File[] files = reportsDir.listFiles((dir, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
        if (files == null) return results;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            for (File file : files) {
                Document doc = builder.parse(file);
                doc.getDocumentElement().normalize();
                NodeList testCases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < testCases.getLength(); i++) {
                    Element tc = (Element) testCases.item(i);
                    String name = tc.getAttribute("name");
                    String status = "PASS";
                    if (tc.getElementsByTagName("failure").getLength() > 0) {
                        status = "FAIL";
                    } else if (tc.getElementsByTagName("error").getLength() > 0) {
                        status = "ERROR";
                    } else if (tc.getElementsByTagName("skipped").getLength() > 0) {
                        status = "SKIP";
                    }
                    results.add(new TestResult(name, status));
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }
}
