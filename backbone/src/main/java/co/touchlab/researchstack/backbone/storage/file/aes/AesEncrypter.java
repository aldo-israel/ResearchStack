package co.touchlab.researchstack.backbone.storage.file.aes;
import com.tozny.crypto.android.AesCbcWithIntegrity;

import java.security.GeneralSecurityException;

/**
 * Created by bradleymcdermott on 1/26/16.
 */
public class AesEncrypter implements Encrypter
{
    private AesCbcWithIntegrity.SecretKeys secretKeys;

    public AesEncrypter(AesCbcWithIntegrity.SecretKeys secretKeys)
    {
        this.secretKeys = secretKeys;
    }

    @Override
    public byte[] encrypt(byte[] data) throws GeneralSecurityException
    {
        return AesCbcWithIntegrity.encrypt(data, secretKeys).toString().getBytes();
    }

    @Override
    public byte[] decrypt(byte[] data) throws GeneralSecurityException
    {
        String encrypted = new String(data);
        AesCbcWithIntegrity.CipherTextIvMac cipherText = new AesCbcWithIntegrity.CipherTextIvMac(
                encrypted);
        return AesCbcWithIntegrity.decrypt(cipherText, secretKeys);
    }
}