package lu.boud.gymsync.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Shapes verified against live responses on 2026-10-28 (see API_NOTES.md). Unknown keys are ignored everywhere. */

@Serializable
data class ApiError(
    val type: String? = null,
    val errorMessage: String? = null,
    val field: String? = null,
    val details: String? = null,
)

/** Generic envelope: `data` is absent for unknown resources, `errors[]` may appear with HTTP 200. */
@Serializable
data class Envelope<T>(
    val data: T? = null,
    val errors: List<ApiError>? = null,
    val token: String? = null,
    val expireIn: Long? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val keepMeLoggedIn: Boolean = true,
)

@Serializable
data class LoginUser(val id: String)

@Serializable
data class LoginFacility(
    val id: String,
    val url: String,
    val name: String? = null,
    val isDemo: Boolean = false,
)

@Serializable
data class LoginResponse(
    val result: String? = null,
    val token: String? = null,
    val userContext: LoginUser? = null,
    val facilities: List<LoginFacility>? = null,
)

@Serializable
data class ActivityHistoryRequest(
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val pager: String? = null,
)

@Serializable
data class ActivityItem(
    val id: String,
    val type: String,
    val partitionDate: Int,
    val hour: Int,
    val minute: Int,
    val name: String? = null,
    val calories: Int? = null,
    val totalDurationInMinutes: Int? = null,
    val idCr: Long,
    val facilityId: String? = null,
)

@Serializable
data class ActivityHistory(
    val pager: String? = null,
    val items: List<ActivityItem> = emptyList(),
)

@Serializable
data class SessionDetailRequest(val idCr: Long, val partitionDate: Int)

@Serializable
data class PhysicalProperty(
    val physicalProperty: String,
    val value: Double? = null,
    val unitOfMeasure: String? = null,
    val formattedValue: String? = null,
)

@Serializable
data class PerformedData(
    val data: List<PhysicalProperty> = emptyList(),
    val steps: List<JsonElement> = emptyList(),
)

@Serializable
data class PerformedActivity(
    val doneOn: String? = null,
    val doneAs: String? = null,
    val data: PerformedData? = null,
)

@Serializable
data class PhysicalActivity(
    val position: Int? = null,
    val isCardio: Boolean = false,
    val physicalActivityType: String? = null,
    val physicalActivityName: String? = null,
    val equipmentName: String? = null,
    val equipmentType: String? = null,
    val status: String? = null,
    val performedPhysicalActivity: PerformedActivity? = null,
)

@Serializable
data class SessionDetail(
    val idCr: Long,
    val displayName: String? = null,
    val name: String? = null,
    val startedOn: String,
    val closedOn: String? = null,
    val physicalActivities: List<PhysicalActivity> = emptyList(),
    val doneSummary: List<PhysicalProperty> = emptyList(),
)
