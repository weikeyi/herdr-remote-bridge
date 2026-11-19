package dev.herdrbridge.nativeclient;

import java.io.IOException;

/** Package-only persistence seam; production uses DeviceVault exclusively. */
interface CredentialStore {
    boolean hasRecord();
    void save(CredentialRecord record) throws IOException;
}
