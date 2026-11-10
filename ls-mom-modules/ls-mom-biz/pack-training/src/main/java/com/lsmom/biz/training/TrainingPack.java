package com.lsmom.biz.training;

/** 培训考试 Pack 的编码常量。核心中不出现这些概念（DOM-05）。 */
public final class TrainingPack {

    public static final String CODE = "ls.training";
    public static final String COURSE = "ls.training.Course";
    public static final String QUESTION = "ls.training.Question";
    public static final String OPTION = "ls.training.QuestionOption";
    public static final String EXAM = "ls.training.Exam";
    public static final String EXAM_QUESTION = "ls.training.ExamQuestion";
    public static final String CANDIDATE = "ls.training.ExamCandidate";
    public static final String ANSWER = "ls.training.Answer";
    public static final String ANSWER_ITEM = "ls.training.AnswerItem";
    public static final String RECORD = "ls.training.TrainingRecord";

    /** 公开 Operation：其他 Pack 的资质守卫经此查询有效培训记录，而非直接读表。 */
    public static final String QUALIFICATION = "ls.training.TrainingRecord.qualification";
    public static final String START = "ls.training.Exam.start";
    public static final String DRAFT_BATCH = "ls.training.Question.draftBatch";

    private TrainingPack() {
    }
}
