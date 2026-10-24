package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.memory.BuddyMemoryService.Candidate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BuddyPreferenceExtractorTest {
    @Test
    void explicitGoalAndTeachingPreferenceAreExactSubstrings() {
        String message = "我的目标岗位是Java后端校招，我喜欢先举例再讲原理。请讲讲volatile。";
        var candidates = BuddyPreferenceExtractor.explicit(message, "coach");
        assertThat(candidates).extracting(Candidate::key).containsExactly("target_role", "explanation_style");
        assertThat(candidates).extracting(Candidate::value).containsExactly("Java后端校招", "先举例再讲原理");
        candidates.forEach(c -> {
            assertThat(message).contains(c.evidence());
            assertThat(c.evidence()).contains(c.value());
        });
    }

    @Test
    void ongoingSupportPreferenceIsRecognizedWithoutModelMetadata() {
        var candidates = BuddyPreferenceExtractor.explicit("我只想抱怨，不要建议。以后我难过时请先听我说，别急着给建议。", "companion");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).key()).isEqualTo("support_style");
        assertThat(candidates.get(0).value()).isEqualTo("先听我说，别急着给建议");
    }

    @Test
    void absentNegatedThirdPartyOrForbiddenPreferencesAreNotInvented() {
        for (String message : new String[]{"我今天很难过", "我不喜欢先举例", "不是我喜欢先举例", "朋友说我喜欢先举例", "我喜欢看电影", "别记住，我喜欢先举例"}) {
            assertThat(BuddyPreferenceExtractor.explicit(message, "coach")).as(message).isEmpty();
        }
    }

    @Test
    void roleSpecificPreferencesDoNotCrossModes() {
        assertThat(BuddyPreferenceExtractor.explicit("我喜欢先举例再讲原理", "companion")).isEmpty();
        assertThat(BuddyPreferenceExtractor.explicit("以后我难过时请先听我说", "coach")).isEmpty();
    }
}
