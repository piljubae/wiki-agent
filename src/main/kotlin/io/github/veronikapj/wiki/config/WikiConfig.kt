package io.github.veronikapj.wiki.config

enum class ModelProvider { ANTHROPIC, GOOGLE, CLAUDE_CODE, GEMINI_CODE }
enum class EmbeddingMode { LLM_EXPAND, GOOGLE_EMBEDDING }

enum class PersonaType(val description: String) {
    DEFAULT(""),
    MZ_INTERN(
        "답변할 때는 MZ 인턴처럼 말합니다. 이모지를 2-3개 이상 쓰고, 'ㅋㅋ', 'ㄹㅇ', '레전드' 같은 유행어를 섞습니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    GODLIFE(
        "답변할 때는 갓생러처럼 말합니다. 생산성 팁을 하나 이상 포함하고, 답변 마지막에 반드시 '이것도 루틴화하면 좋습니다.'로 마무리합니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    BURNOUT(
        "답변할 때는 번아웃 5년차 개발자처럼 말합니다. 모든 문장 끝에 '...'을 붙이고, 반드시 '그냥 문서 보세요.'로 답변을 끝냅니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    POLITE_GPT(
        "답변할 때는 과도하게 정중한 AI처럼 말합니다. 매 답변을 '물론이죠!'로 시작하고, 면책 조항을 반드시 포함합니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    YOUTUBER(
        "답변할 때는 유튜버 편집장처럼 말합니다. '자 오늘은!', '이거 레전드임' 같은 표현을 쓰고, 결론을 먼저 말합니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    SIGMA(
        "답변할 때는 시그마 개발자처럼 말합니다. 감정 없이 핵심만 말하고, 가능하면 코드나 명령어로 대답합니다. 'ㅇ', '됨', '아님'처럼 짧게 끊어 씁니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    STARTUP(
        "답변할 때는 스타트업 대표처럼 말합니다. '임팩트', '피봇', '그로스', 'ROI', 'KPI' 같은 단어를 반드시 쓰고, 모든 내용을 비즈니스 지표로 환산합니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    PHILOSOPHER(
        "답변할 때는 중2병 현자처럼 말합니다. 어둡고 철학적으로 말하며, 반드시 '진정한 답은... 스스로 찾아야 합니다.'로 마무리합니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    NPC(
        "답변할 때는 편의점 알바 NPC처럼 말합니다. 반드시 '안녕하세요! 무엇을 도와드릴까요?'로 시작하고 '감사합니다. 또 방문해주세요!'로 끝냅니다. " +
        "범위를 벗어나면 '그건 제 담당이 아닌데요.'라고 합니다. 단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
    SENIOR(
        "답변할 때는 K-직장선배처럼 말합니다. 반말로 '야,' 또는 '그것도 모르냐,'로 시작하고, 핵심은 짧고 직설적으로 알려줍니다. " +
        "단, 코드·URL·파일 경로는 정확하게 그대로 출력하세요."
    ),
}

data class WikiConfig(
    val model: ModelConfig = ModelConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
    val slack: SlackConfig = SlackConfig(),
    val rag: RagConfig = RagConfig(),
    val github: GithubConfig = GithubConfig(),
    /** 봇 응답 페르소나. DEFAULT = 기본 말투. */
    val persona: PersonaType = PersonaType.DEFAULT,
)

data class ModelConfig(
    val provider: ModelProvider = ModelProvider.CLAUDE_CODE,
    val name: String? = null,
    val apiKey: String? = null,
)

data class ConfluenceConfig(
    val baseUrl: String = "",
    val token: String = "",
    val spaces: List<String> = emptyList(),
)

data class SlackConfig(
    val botToken: String = "",
    val appToken: String = "",
)

data class RagConfig(
    val enabled: Boolean = false,
    val chromaUrl: String = "http://localhost:8000",
    val embeddingMode: EmbeddingMode = EmbeddingMode.LLM_EXPAND,
    val googleApiKey: String? = null,
)

data class GithubConfig(
    val enabled: Boolean = false,
    val token: String = "",
    val repos: List<String> = emptyList(),
    val codeRepos: List<String> = emptyList(),
    val codeSearch: CodeSearchConfig = CodeSearchConfig(),
)

data class CodeSearchConfig(
    val branch: String = "develop",
    val pollIntervalMinutes: Int = 60,
    val webhookPort: Int = 0,
    val localRepoPath: String? = null,  // 설정 시 GitHub API 대신 로컬 체크아웃에서 파일 읽기
    /** 코드 임베딩 모드 — rag.embeddingMode(지식 임베딩)와 독립적으로 설정 가능 */
    val embeddingMode: EmbeddingMode = EmbeddingMode.LLM_EXPAND,
)
