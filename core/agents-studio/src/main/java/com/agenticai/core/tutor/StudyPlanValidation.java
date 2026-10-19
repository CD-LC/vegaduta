package com.agenticai.core.tutor;

import com.agenticai.core.tutor.StudyPlanDocs.Curriculum;
import com.agenticai.core.tutor.StudyPlanDocs.Lesson;
import com.agenticai.core.tutor.StudyPlanDocs.LessonContent;
import com.agenticai.core.tutor.StudyPlanDocs.Module;
import com.agenticai.core.tutor.StudyPlanDocs.QuizItem;
import com.agenticai.core.tutor.dto.TutorDtos.LessonContentInput;
import com.agenticai.core.tutor.dto.TutorDtos.LessonInput;
import com.agenticai.core.tutor.dto.TutorDtos.ModuleInput;
import com.agenticai.core.tutor.dto.TutorDtos.QuizInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation + normalisation of everything that becomes a stored study plan - whether a caller
 * supplied it or a model wrote it. Both sources are untrusted: text is trimmed and length-capped,
 * quiz answer keys are range-checked, and ids are always assigned here, never taken from input.
 * Every failure is an {@link IllegalArgumentException} (HTTP 400) with a plain message.
 */
public final class StudyPlanValidation {

    public static final int MAX_TOPIC_CHARS = 300;
    public static final int MIN_TOPIC_CHARS = 2;
    public static final int MAX_GOAL_CHARS = 500;
    public static final int MAX_TITLE_CHARS = 200;
    public static final int MAX_OBJECTIVE_CHARS = 500;
    public static final int MAX_MODULES = 12;
    public static final int MAX_EXPLANATION_CHARS = 20_000;
    public static final int MAX_WORKED_EXAMPLE_CHARS = 6_000;
    public static final int MAX_KEY_POINTS = 10;
    public static final int MAX_KEY_POINT_CHARS = 400;
    public static final int MAX_QUIZ_QUESTIONS = 8;
    public static final int MIN_QUIZ_OPTIONS = 2;
    public static final int MAX_QUIZ_OPTIONS = 6;
    public static final int MAX_QUESTION_CHARS = 1_000;
    public static final int MAX_OPTION_CHARS = 500;
    public static final int MAX_ANSWER_EXPLANATION_CHARS = 2_000;

    public static final String DEFAULT_LANGUAGE = "en";
    public static final String DEFAULT_LEVEL = "beginner";
    private static final Set<String> LEVELS = Set.of("beginner", "intermediate", "advanced");
    /** A BCP-47-shaped tag ("hi", "kn", "pt-BR", "zh-Hant"); the column is VARCHAR(16). */
    private static final Pattern LANGUAGE_TAG = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$");

    private StudyPlanValidation() {
    }

    public static String topic(String raw) {
        String topic = clean(raw);
        if (topic.length() < MIN_TOPIC_CHARS) {
            throw new IllegalArgumentException("Tell the tutor what you want to learn.");
        }
        if (topic.length() > MAX_TOPIC_CHARS) {
            throw new IllegalArgumentException("The topic can be at most " + MAX_TOPIC_CHARS + " characters.");
        }
        return topic;
    }

    /** Null/blank means {@link #DEFAULT_LANGUAGE}; anything else must look like a language tag. */
    public static String language(String raw) {
        String tag = clean(raw).replace('_', '-');
        if (tag.isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        if (tag.length() > 16 || !LANGUAGE_TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("language must be a language code such as en, hi or pt-BR.");
        }
        return tag;
    }

    public static String level(String raw) {
        String level = clean(raw).toLowerCase(Locale.ROOT);
        if (level.isEmpty()) {
            return DEFAULT_LEVEL;
        }
        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException("level must be beginner, intermediate or advanced.");
        }
        return level;
    }

    public static String goal(String raw) {
        String goal = clean(raw);
        if (goal.isEmpty()) {
            return null;
        }
        if (goal.length() > MAX_GOAL_CHARS) {
            throw new IllegalArgumentException("The goal can be at most " + MAX_GOAL_CHARS + " characters.");
        }
        return goal;
    }

    /**
     * Builds the stored curriculum from supplied modules: assigns ids ({@code m1}, {@code m1-l1},
     * {@code q1}), drops nothing silently, and refuses an empty or oversized outline.
     */
    public static Curriculum curriculum(List<ModuleInput> modules, int maxLessons) {
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("A study plan needs at least one module.");
        }
        if (modules.size() > MAX_MODULES) {
            throw new IllegalArgumentException("A study plan can have at most " + MAX_MODULES + " modules.");
        }
        List<Module> out = new ArrayList<>();
        int totalLessons = 0;
        for (int m = 0; m < modules.size(); m++) {
            ModuleInput module = modules.get(m);
            if (module == null) {
                throw new IllegalArgumentException("Module " + (m + 1) + " is empty.");
            }
            String moduleId = "m" + (m + 1);
            String title = required(module.title(), MAX_TITLE_CHARS, "Module " + (m + 1) + " needs a title.");
            if (module.lessons() == null || module.lessons().isEmpty()) {
                throw new IllegalArgumentException("Module " + (m + 1) + " needs at least one lesson.");
            }
            List<Lesson> lessons = new ArrayList<>();
            for (int l = 0; l < module.lessons().size(); l++) {
                LessonInput lesson = module.lessons().get(l);
                if (lesson == null) {
                    throw new IllegalArgumentException("Module " + (m + 1) + " has an empty lesson.");
                }
                totalLessons++;
                if (totalLessons > maxLessons) {
                    throw new IllegalArgumentException("A study plan can have at most " + maxLessons + " lessons.");
                }
                String lessonTitle = required(lesson.title(), MAX_TITLE_CHARS,
                    "Lesson " + (l + 1) + " of module " + (m + 1) + " needs a title.");
                LessonContent content = lesson.content() == null ? null : lessonContent(lesson.content());
                List<QuizItem> quiz = lesson.quiz() == null || lesson.quiz().isEmpty() ? null : quiz(lesson.quiz());
                if (content == null && quiz != null) {
                    throw new IllegalArgumentException("Lesson '" + lessonTitle + "' has a quiz but no explanation.");
                }
                lessons.add(new Lesson(
                    moduleId + "-l" + (l + 1), lessonTitle, truncate(clean(lesson.objective()), MAX_OBJECTIVE_CHARS),
                    StudyPlanDocs.LESSON_NOT_STARTED, content, quiz));
            }
            out.add(new Module(moduleId, title, lessons));
        }
        return new Curriculum(out);
    }

    public static LessonContent lessonContent(LessonContentInput input) {
        if (input == null) {
            throw new IllegalArgumentException("The lesson has no content.");
        }
        String explanation = clean(input.explanation());
        if (explanation.isEmpty()) {
            throw new IllegalArgumentException("The lesson explanation is empty.");
        }
        if (explanation.length() > MAX_EXPLANATION_CHARS) {
            throw new IllegalArgumentException(
                "The lesson explanation can be at most " + MAX_EXPLANATION_CHARS + " characters.");
        }
        List<String> keyPoints = new ArrayList<>();
        if (input.keyPoints() != null) {
            for (String point : input.keyPoints()) {
                String cleaned = truncate(clean(point), MAX_KEY_POINT_CHARS);
                if (!cleaned.isEmpty() && keyPoints.size() < MAX_KEY_POINTS) {
                    keyPoints.add(cleaned);
                }
            }
        }
        String worked = truncate(clean(input.workedExample()), MAX_WORKED_EXAMPLE_CHARS);
        return new LessonContent(explanation, keyPoints, worked.isEmpty() ? null : worked);
    }

    /** Validates a quiz and assigns question ids {@code q1..qN}. The answer key must point at a real option. */
    public static List<QuizItem> quiz(List<QuizInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("The quiz has no questions.");
        }
        if (inputs.size() > MAX_QUIZ_QUESTIONS) {
            throw new IllegalArgumentException("A quiz can have at most " + MAX_QUIZ_QUESTIONS + " questions.");
        }
        List<QuizItem> out = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            QuizInput input = inputs.get(i);
            String label = "Question " + (i + 1);
            if (input == null) {
                throw new IllegalArgumentException(label + " is empty.");
            }
            String question = required(input.question(), MAX_QUESTION_CHARS, label + " has no text.");
            if (input.options() == null
                || input.options().size() < MIN_QUIZ_OPTIONS || input.options().size() > MAX_QUIZ_OPTIONS) {
                throw new IllegalArgumentException(
                    label + " needs between " + MIN_QUIZ_OPTIONS + " and " + MAX_QUIZ_OPTIONS + " options.");
            }
            List<String> options = new ArrayList<>();
            for (String option : input.options()) {
                options.add(required(option, MAX_OPTION_CHARS, label + " has an empty option."));
            }
            if (input.correctIndex() == null || input.correctIndex() < 0 || input.correctIndex() >= options.size()) {
                throw new IllegalArgumentException(label + " has a correctIndex that is not one of its options.");
            }
            out.add(new QuizItem(
                "q" + (i + 1), question, options, input.correctIndex(),
                truncate(clean(input.explanation()), MAX_ANSWER_EXPLANATION_CHARS), null));
        }
        return out;
    }

    private static String required(String raw, int maxChars, String messageWhenEmpty) {
        String value = clean(raw);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(messageWhenEmpty);
        }
        return truncate(value, maxChars);
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.strip();
    }

    private static String truncate(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }
}
