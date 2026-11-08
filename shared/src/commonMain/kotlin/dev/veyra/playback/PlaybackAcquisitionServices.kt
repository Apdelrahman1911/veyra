package dev.veyra.playback

import dev.veyra.acquisition.AcquisitionAdmissionFactory
import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionCoordinator
import dev.veyra.acquisition.QueueAcquisitionCoordinator
import dev.veyra.data.AcquisitionLibraryRepository

/** Explicit schema3 services; none creates a client, database, scheduler or native owner. */
class PlaybackAcquisitionServices(
    val repository: AcquisitionLibraryRepository,
    val acquisition: AcquisitionCoordinator,
    val queues: QueueAcquisitionCoordinator,
    val resolver: AcquisitionPlaybackResolver,
    val captures: AcquisitionCaptureFactory = AcquisitionCaptureFactory(),
    val admissions: AcquisitionAdmissionFactory = AcquisitionAdmissionFactory(captures),
) {
    init {
        require(repository.transactionOwner === acquisition.transactionOwner &&
            repository.transactionOwner === queues.transactionOwner &&
            repository.transactionOwner === resolver.transactionOwner) { "Playback requires one Room owner" }
    }
}
