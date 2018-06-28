package ch.epfl.dedis.cothority.demo.cothoritydemo;

import ch.epfl.dedis.lib.Roster;
import ch.epfl.dedis.lib.SkipblockId;
import ch.epfl.dedis.lib.crypto.Hex;
import ch.epfl.dedis.lib.exception.CothorityCryptoException;
import ch.epfl.dedis.lib.exception.CothorityException;
import ch.epfl.dedis.lib.omniledger.InstanceId;
import ch.epfl.dedis.lib.omniledger.OmniledgerRPC;
import ch.epfl.dedis.lib.omniledger.darc.DarcId;
import ch.epfl.dedis.lib.omniledger.darc.Signer;
import ch.epfl.dedis.lib.omniledger.darc.SignerEd25519;

/**
 * For testing with our deployed servers, you may use this class. You will need to ask
 * for the details to fill in here.
 * </pre>
 */
public final class SecureKG {
    /**
     * Gets the roster of the secure KG server.
     * @return the roster
     */
    public static Roster getRoster() {
        return Roster.FromToml("(fill this in)");
    }

    /**
     * Gets the genesis skipblock ID of an existing omniledger service.
     * @return the genesis skipblock ID
     */
    public static SkipblockId getSkipchainId() throws CothorityCryptoException {
        return new SkipblockId(Hex.parseHexBinary("(fill this in)"));
    }

    /**
     * Gets the signer that has "invoke:eventlog" and "spawn:eventlog" permissions.
     */
    public static Signer getSigner() {
        return new SignerEd25519(Hex.parseHexBinary("(fill this in)"));
    }

    /**
     * Gets the darc ID that has the "invoke:eventlog" and "spawn:eventlog" rules.
     * @return the darc ID
     */
    public static DarcId getDarcId() throws CothorityCryptoException {
        return new DarcId(Hex.parseHexBinary("(fill this in)"));
    }

    /**
     * Gets the eventlog instance ID.
     * @return the instance ID.
     */
    public static InstanceId getEventlogId() throws CothorityCryptoException {
        return new InstanceId(Hex.parseHexBinary("(fill this in)"));
    }

    /**
     * Get the pre-configured omniledger RPC.
     * @return the omniledger RPC object
     */
    public static OmniledgerRPC getOmniledgerRPC() throws CothorityException {
        return new OmniledgerRPC(getRoster(), getSkipchainId());
    }
}

