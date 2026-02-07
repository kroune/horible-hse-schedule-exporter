import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class Lesson(
    val id: String,
    val building: String,
    val type: String,
    val stream: String,
    @SerialName("auditorium_id")
    val auditoriumId: Int,
    val auditorium: String,
    val city: String,
    @SerialName("date_start")
    val dateStart: String,
    @SerialName("date_end")
    val dateEnd: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("importance_level")
    val importanceLevel: Int,
    @SerialName("building_id")
    val buildingId: Int,
    val location: Location,
    val discipline: String,
    @SerialName("group_id")
    val groupId: String? = null,
    @SerialName("discipline_link")
    val disciplineLink: String? = null,
    @SerialName("lesson_number_start")
    val lessonNumberStart: Int,
    @SerialName("lesson_number_end")
    val lessonNumberEnd: Int,
    val duration: List<Int>,
    @SerialName("lecturer_emails")
    val lecturerEmails: List<String> = emptyList(),
    @SerialName("lecturer_profiles")
    val lecturerProfiles: List<LecturerProfile> = emptyList(),
    @SerialName("kindOfWork")
    val kindOfWork: String,
    @SerialName("is_ban")
    val isBan: Boolean,
    val hash: String
)

@Serializable
data class Location(
    val type: String,
    val coordinates: List<Double>
)

@Serializable
data class LecturerProfile(
    val id: String,
    val email: String? = null,
    @SerialName("full_name")
    val fullName: String,
    val description: String
)

// Пример использования
fun main() {
    val jsonString = """[ваш JSON здесь]"""

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val lessons = json.decodeFromString<List<Lesson>>(jsonString)
    lessons.forEach { lesson ->
        println("Discipline: ${lesson.discipline}")
        println("Lecturer: ${lesson.lecturerProfiles.firstOrNull()?.fullName}")
        println("Time: ${lesson.dateStart} - ${lesson.dateEnd}")
        println("---")
    }
}