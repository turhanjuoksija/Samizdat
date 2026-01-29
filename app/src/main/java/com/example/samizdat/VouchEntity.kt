package com.example.samizdat

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vouches", primaryKeys = ["voucherPk", "targetPk"])
data class VouchEntity(
    val voucherPk: String,
    val targetPk: String,
    val signature: String,
    val timestamp: Long
)

@Dao
interface VouchDao {
    @Query("SELECT * FROM vouches WHERE targetPk = :targetPk")
    fun getVouchesForPeer(targetPk: String): Flow<List<VouchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVouch(vouch: VouchEntity)

    @Query("SELECT COUNT(DISTINCT voucherPk) FROM vouches WHERE targetPk = :targetPk")
    suspend fun getVouchCount(targetPk: String): Int

    @Query("SELECT * FROM vouches")
    fun getAllVouches(): Flow<List<VouchEntity>>
}
