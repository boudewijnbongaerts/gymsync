package lu.boud.gymsync.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length

class HcNotReadyException(message: String) : Exception(message)

/** Writes a plan with insertRecords (upsert on clientRecordId). Write-only: never reads or deletes. */
class HcWriter(private val context: Context) {

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
        )

        fun sdkAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        fun hcType(t: HcExerciseType): Int = when (t) {
            HcExerciseType.BIKING_STATIONARY -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
            HcExerciseType.RUNNING_TREADMILL -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            HcExerciseType.ROWING_MACHINE -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
            HcExerciseType.ELLIPTICAL -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
            HcExerciseType.STAIR_CLIMBING_MACHINE -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
            HcExerciseType.STRENGTH_TRAINING -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            HcExerciseType.OTHER_WORKOUT -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }

        fun toRecords(s: PlannedSession): List<Record> {
            val device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "Technogym", model = s.deviceModel.take(50))
            fun meta(id: String) = Metadata.autoRecorded(clientRecordId = id, clientRecordVersion = Mapper.RECORD_VERSION, device = device)
            val out = mutableListOf<Record>(
                ExerciseSessionRecord(
                    startTime = s.start.toInstant(), startZoneOffset = s.start.offset,
                    endTime = s.end.toInstant(), endZoneOffset = s.end.offset,
                    exerciseType = hcType(s.type), title = s.title, metadata = meta(s.clientRecordId),
                ),
                ActiveCaloriesBurnedRecord(
                    startTime = s.start.toInstant(), startZoneOffset = s.start.offset,
                    endTime = s.end.toInstant(), endZoneOffset = s.end.offset,
                    energy = Energy.kilocalories(s.kcal), metadata = meta(s.calClientRecordId),
                ),
            )
            val d = s.distanceMeters
            if (d != null && d > 0.0) out += DistanceRecord(
                startTime = s.start.toInstant(), startZoneOffset = s.start.offset,
                endTime = s.end.toInstant(), endZoneOffset = s.end.offset,
                distance = Length.meters(d), metadata = meta(s.clientRecordId.replaceFirst(":", ":dist:")),
            )
            return out
        }
    }

    suspend fun grantedAll(): Boolean {
        if (!sdkAvailable(context)) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }

    /** Inserts all records of all sessions in one call. Throws HcNotReadyException if HC or permissions are missing. */
    suspend fun write(sessions: List<PlannedSession>): Int {
        if (sessions.isEmpty()) return 0
        if (!sdkAvailable(context)) throw HcNotReadyException("Health Connect is not available")
        val client = HealthConnectClient.getOrCreate(context)
        if (!client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) {
            throw HcNotReadyException("Health Connect write permission missing")
        }
        val records = sessions.flatMap { toRecords(it) }
        client.insertRecords(records)
        return sessions.size
    }
}
