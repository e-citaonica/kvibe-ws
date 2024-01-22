package net.kvibews.service

import com.corundumstudio.socketio.SocketIOClient
import net.kvibews.config.ApplicationProperties
import net.kvibews.document.SubmitRequest
import net.kvibews.dto.DocumentDTO
import net.kvibews.exception.DocumentNotFoundException
import net.kvibews.exception.InvalidOperationRevision
import net.kvibews.model.DocumentState
import net.kvibews.model.OperationWrapper
import net.kvibews.model.TextOperation
import net.kvibews.model.TextSelection
import net.kvibews.operation_transformations.OperationTransformations
import net.kvibews.repository.DocumentRepository
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.*


@Service
class DocumentService(
    val documentRepo: DocumentRepository,
    val eventDispatcherService: EventDispatcherService,
    val props: ApplicationProperties,
    val logger: Logger,
    @Qualifier("string") val opTransformations: OperationTransformations
) {

    fun joinDocument(documentId: String, sessionId: UUID, username: String) {
        documentRepo.addActiveUser(documentId, sessionId, username)
    }

    fun getActiveUsername(documentId: String, sessionId: UUID): String? {
        return documentRepo.getActiveUser(documentId, sessionId)
    }

    fun leaveDocument(documentId: String, sessionId: UUID) {
        documentRepo.removeActiveUser(documentId, sessionId)
    }

    fun createDocument(createDocument: DocumentDTO.Create): DocumentState {
        val documentId = UUID.randomUUID().toString()

        val document = DocumentState(documentId, createDocument.name, createDocument.language)
        documentRepo.setDocument(documentId, document)

        return document
    }

    fun getDocument(documentId: String): DocumentState {
        return documentRepo.getDocument(documentId) ?: throw DocumentNotFoundException(documentId)
    }

    fun submit(operationWrapper: OperationWrapper, userSession: SocketIOClient): Pair<Int, List<TextOperation>>? {
        var retry = 0
        var request: SubmitRequest
        var success: Boolean

        do {
            retry++
            val snapshot = documentRepo.getDocument(operationWrapper.docId) ?: return null

            request = SubmitRequest(
                operation = operationWrapper,
                snapshot = snapshot,
                opTransformations = opTransformations
            )

            success = tryApply(request)
        } while (retry < props.operation.maxNumberOfRetries && !success)

        if (success) {
            val ops = request.operations.subList(request.snapshot.revision, request.operations.size)
            ops.forEach {
                eventDispatcherService.dispatch(
                    OperationWrapper(
                        operation = it,
                        docId = request.snapshot.id,
                        revision = request.revision,
                        performedBy = operationWrapper.performedBy
                    ), userSession
                )
            }
            return Pair(request.revision, ops)
        }
        return null
    }

    fun tryApply(
        request: SubmitRequest
    ): Boolean {
        val ops = request.transform()

        ops.forEach {
            request.apply(it)
        }

        return documentRepo.compareAndSet(request.snapshot.id, request.snapshot.revision, request.getTransformed())
    }

    fun transform(selection: TextSelection): TextSelection? {
        val snapshot = documentRepo.getDocument(selection.docId) ?: return null

        if (selection.revision > snapshot.revision) {
            throw InvalidOperationRevision(
                "Attempted to perform operation with greater revision (${selection.revision}) than current document revision ${snapshot.revision}"
            )
        }

        var transformedSelection = selection

        for (revision in selection.revision until snapshot.operations.size) {
            transformedSelection = opTransformations.transform(transformedSelection, snapshot.operations[revision])
        }

        return transformedSelection
    }
}