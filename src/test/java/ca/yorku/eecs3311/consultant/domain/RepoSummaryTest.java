package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepoSummaryTest {

    @Test
    void unavailableCarriesTheReasonSoFailuresStayDistinct() {
        RepoSummary s = RepoSummary.unavailable("https://github.com/x/y", "repository is private");
        assertFalse(s.isCodeAvailable());
        assertEquals("repository is private", s.getNote());
        assertFalse(s.isReproducible());
    }

    @Test
    void reproducibleRequiresCodeDataAndEvalScriptsTogether() {
        RepoSummary s = new RepoSummary();
        s.setCodeAvailable(true);
        s.setHasDatasets(true);
        assertFalse(s.isReproducible(), "code and data alone cannot reproduce a result");
        s.setHasEvalScripts(true);
        assertTrue(s.isReproducible());
    }

    @Test
    void readmeQualityIsNeverBlank() {
        RepoSummary s = new RepoSummary();
        assertEquals("unknown", s.getReadmeQuality());
        s.setReadmeQuality("  ");
        assertEquals("unknown", s.getReadmeQuality());
    }

    @Test
    void metadataLookupIsCaseInsensitiveAndLicenseNoneIsNotALicense() {
        RepoMetadata m = new RepoMetadata("https://github.com/x/y");
        m.setTopLevelFiles(List.of("README.md", "eval", "data"));
        assertTrue(m.hasTopLevelEntry("readme.md"));
        assertTrue(m.hasTopLevelEntry("  EVAL "));
        assertFalse(m.hasTopLevelEntry("src"));

        assertFalse(m.hasLicense());
        m.setLicense("none");
        assertFalse(m.hasLicense(), "\"none\" is an answer, not a licence");
        m.setLicense("MIT");
        assertTrue(m.hasLicense());
    }
}
