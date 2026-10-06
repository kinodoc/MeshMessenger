package com.example.meshmessenger.mesh

import java.util.UUID

object MeshProtocol {
    val BLUETOOTH_SERVICE_UUID: UUID = UUID.fromString("7d2a1000-8b4f-4f10-9d3e-8b7d6a2f0001")
    val RFCOMM_SERVICE_UUID: UUID = UUID.fromString("7d2a1004-8b4f-4f10-9d3e-8b7d6a2f0001")
    const val MAX_PACKET = 4096
}
