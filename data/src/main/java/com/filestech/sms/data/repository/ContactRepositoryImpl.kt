package com.filestech.sms.data.repository

import com.filestech.sms.data.contacts.ContactsReader
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import com.filestech.sms.domain.repository.ContactRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val reader: ContactsReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ContactRepository {

    override suspend fun lookupByPhone(rawPhone: String): Contact? = withContext(io) {
        reader.lookupByPhone(rawPhone)
    }

    override suspend fun listAll(): List<Contact> = withContext(io) { reader.listAll() }

    override suspend fun listAllDetailed(): List<Contact> = withContext(io) {
        reader.listAllDetailed()
    }

    override suspend fun openPhotoStream(address: String): InputStream? = withContext(io) {
        reader.openPhotoStream(address)
    }

    override suspend fun setContactPhoto(
        address: String,
        photoBytes: ByteArray,
    ): ContactPhotoWriteResult = withContext(io) {
        reader.setPhoto(address, photoBytes)
    }
}
