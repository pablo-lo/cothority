package ch.epfl.dedis.ocs;

import ch.epfl.dedis.lib.Roster;
import ch.epfl.dedis.lib.ServerIdentity;
import ch.epfl.dedis.lib.SkipblockId;
import ch.epfl.dedis.lib.crypto.Ed25519Point;
import ch.epfl.dedis.lib.crypto.Hex;
import ch.epfl.dedis.lib.crypto.Point;
import ch.epfl.dedis.lib.darc.*;
import ch.epfl.dedis.lib.exception.CothorityCommunicationException;
import ch.epfl.dedis.lib.exception.CothorityCryptoException;
import ch.epfl.dedis.proto.DarcOCSProto;
import ch.epfl.dedis.proto.OCSProto;
import ch.epfl.dedis.proto.SkipBlockProto;
import ch.epfl.dedis.proto.SkipchainProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * dedis/lib
 * OnchainSecretsRPC.java
 * Purpose: Implementing all communication with the cothority to set-up,
 * check, write and read documents from the skipchain.
 *
 * @author Linus Gasser <linus.gasser@epfl.ch>
 * @version 0.3 2017/11/13
 */

public class OnchainSecretsRPC {
    // ocsID is the current getId of the onchain-secret skipchain. For multiple
    // runs on the ocs-skipchain, this getId must be initialized to the same
    // value as before.
    protected SkipblockId ocsID;
    // X is the public symmetricKey of the ocs-shard that will re-encrypt the symmetric
    // keys if they receive a valid re-encryption request.
    protected Point X;

    // adminDarc points to the latest darc describing the admin.
    protected Darc adminDarc;

    // the roster that holds the current skipchain
    protected Roster roster;
    private final Logger logger = LoggerFactory.getLogger(OnchainSecretsRPC.class);

    /**
     * If the skipchain is already initialised, this constructor will only
     * initialise the class. Once it is initialized, you can verify it with
     * the verify()-method. This constructor will search for the shared
     * public symmetricKey of the ocs-shard.
     *
     * @param roster list of all cothority servers with public keys
     * @param ocsID  the getId of the used skipchain
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    OnchainSecretsRPC(Roster roster, SkipblockId ocsID) throws CothorityCommunicationException {
        this.ocsID = ocsID;
        this.roster = roster;
        this.X = getSharedPublicKey();

        try {
            OCSProto.Transaction transaction = getTransaction(ocsID);
            adminDarc = new Darc(transaction.getDarc());
            logger.info("adminDarc is: " + adminDarc.toString());
        } catch (CothorityCryptoException e) {
            throw new CothorityCommunicationException(e.toString());
        }
    }

    /**
     * This constructor will create a new onchain-secrets skipchain and initialize
     * all local parameters to fit the new values generated by the ocs-skipchain.
     *
     * @param roster list of all cothority servers with public keys
     * @param admins is a darc where the owners are the admins and the
     *               users are allowed to write new documents to the chain
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    OnchainSecretsRPC(Roster roster, Darc admins) throws CothorityCommunicationException {
        this.roster = roster;
        createSkipchains(admins); // this call internally sets ocsID and X
        this.adminDarc = admins;
    }

    /**
     * Contacts all nodes in the cothority and returns true only if _all_
     * nodes returned OK.
     *
     * @return true only if all nodes are OK, else false.
     */
    public boolean verify() {
        boolean ok = true;
        for (ServerIdentity n : roster.getNodes()) {
            logger.info("Testing node {}", n.getAddress());
            try {
                n.GetStatus();
            } catch (CothorityCommunicationException e) {
                logger.warn("Failing node {}", n.getAddress());
                ok = false;
            }
        }
        return ok;
    }

    /**
     * Creates a new skipchain and sets up a new ocs-shard. If a previous
     * skipchain has been setup, it still exists and can be accessed with
     * another OnchainSecretsRPC-instance.
     *
     * @param writers the darc of allowed writers to the skipchain.
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public void createSkipchains(Darc writers) throws CothorityCommunicationException {
        OCSProto.CreateSkipchainsRequest.Builder request =
                OCSProto.CreateSkipchainsRequest.newBuilder();
        request.setRoster(roster.toProto());
        request.setWriters(writers.toProto());

        ByteString msg = roster.sendMessage("OnChainSecrets/CreateSkipchainsRequest",
                request.build());

        try {
            OCSProto.CreateSkipchainsReply reply = OCSProto.CreateSkipchainsReply.parseFrom(msg);
            X = new Ed25519Point(reply.getX());
            logger.debug("Got reply: {}", reply.toString());
            ocsID = new SkipblockId(reply.getOcs().getHash().toByteArray());
            logger.info("Initialised OCS: {}", ocsID.toString());
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e.toString());
        } catch (CothorityCryptoException e) {
            throw new CothorityCommunicationException(e.toString());
        }
    }

    /**
     * Updates an existing account or adds a new account to the skipchain. This is mostly useful for accounts that
     * need more access-control than just READ_ACCESS for documents.
     * <p>
     *
     * @param newAccount the new account to be added to the skipchain.
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public void updateDarc(Darc newAccount) throws CothorityCommunicationException, CothorityCryptoException {
        OCSProto.UpdateDarc.Builder request =
                OCSProto.UpdateDarc.newBuilder();
        request.setOcs(ByteString.copyFrom(ocsID.getId()));
        request.setDarc(newAccount.toProto());

        ByteString msg = roster.sendMessage("OnChainSecrets/UpdateDarc",
                request.build());

        try {
            OCSProto.UpdateDarcReply reply = OCSProto.UpdateDarcReply.parseFrom(msg);

            logger.debug("received reply: {}", reply.toString());
            logger.info("Updated darc {} stored in block: {}",
                    newAccount.getId().toString(),
                    Hex.printHexBinary(reply.getSb().getHash().toByteArray()));
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * returns the shared symmetricKey of the DKG that must be used to encrypt the
     * symmetric encryption symmetricKey. This will be the same as OnchainSecretsRPC.X
     * stored when creating the skipchain.
     *
     * @return the aggregate public symmetricKey of the ocs-shard
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    //
    public Point getSharedPublicKey() throws CothorityCommunicationException {
        OCSProto.SharedPublicRequest.Builder request =
                OCSProto.SharedPublicRequest.newBuilder();
        request.setGenesis(ByteString.copyFrom(ocsID.getId()));

        ByteString msg = roster.sendMessage("OnChainSecrets/SharedPublicRequest", request.build());

        try {
            OCSProto.SharedPublicReply reply = OCSProto.SharedPublicReply.parseFrom(msg);
            logger.info("Got shared public symmetricKey");
            return new Ed25519Point(reply.getX());
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * Creates a write request on the skipchain. The signature has to be a valid
     * signature of one of the publishers.
     *
     * @param wr        the write-request to store on the skipchain
     * @param signature the publisher with the right to sell read-access to the document
     * @return
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public WriteRequest createWriteRequest(WriteRequest wr, DarcSignature signature) throws CothorityCommunicationException, CothorityCryptoException {
        OCSProto.WriteRequest.Builder request =
                OCSProto.WriteRequest.newBuilder();
        request.setOcs(ByteString.copyFrom(ocsID.getId()));
        request.setWrite(wr.toProto(X, ocsID));
        request.setReaders(wr.owner.toProto());
        request.setSignature(signature.toProto());

        ByteString msg = roster.sendMessage("OnChainSecrets/WriteRequest",
                request.build());

        try {
            OCSProto.WriteReply reply = OCSProto.WriteReply.parseFrom(msg);
            wr.id = new WriteRequestId(reply.getSb().getHash().toByteArray());
            logger.info("Published document " + wr.id.toString());
            return wr;
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * Gets a darc-path starting from the base to the identity given. This darc-path
     * is the shortest, most up-to-date path at the moment of reply. Of course an
     * update might happen just before you actually use it, and your signature might
     * be rejected then.
     *
     * @param base     where to start the path
     * @param identity which identity to find
     * @return a DarcPath
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public SignaturePath getDarcPath(DarcId base, Identity identity, int role) throws CothorityCommunicationException {
        OCSProto.GetDarcPath.Builder request =
                OCSProto.GetDarcPath.newBuilder();
        request.setOcs(ByteString.copyFrom(ocsID.getId()));
        request.setBasedarcid(ByteString.copyFrom(base.getId()));
        request.setIdentity(identity.toProto());
        request.setRole(role);
        ByteString msg = roster.sendMessage("OnChainSecrets/GetDarcPath", request.build());

        try {
            OCSProto.GetDarcPathReply reply = OCSProto.GetDarcPathReply.parseFrom(msg);
            List<Darc> darcs = new ArrayList<>();
            for (DarcOCSProto.Darc d :
                    reply.getPathList()) {
                darcs.add(new Darc(d));
            }
            return new SignaturePath(darcs, identity, role);
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        } catch (Exception e) {
            throw new CothorityCommunicationException(e.toString());
        }
    }

    /**
     * Requests read-access to a document from the cothority. If it is successful, the cothority
     * will store the read-request on the skipblock.
     *
     * @param rr the prepared read request
     * @return the id of the stored request
     * @throws CothorityCommunicationException
     * @throws CothorityCryptoException
     */
    public ReadRequestId createReadRequest(ReadRequest rr) throws CothorityCommunicationException, CothorityCryptoException {
        OCSProto.ReadRequest.Builder request =
                OCSProto.ReadRequest.newBuilder();
        request.setOcs(ByteString.copyFrom(ocsID.getId()));
        request.setRead(rr.ToProto());

        ByteString msg = roster.sendMessage("OnChainSecrets/ReadRequest", request.build());

        try {
            OCSProto.ReadReply reply = OCSProto.ReadReply.parseFrom(msg);
            logger.info("Created a read-request");
            return new ReadRequestId(reply.getSb().getHash().toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * Returns the skipblock from the skipchain, given its id.
     *
     * @param id the id of the skipblock
     * @return the proto-representation of the skipblock.
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public SkipBlockProto.SkipBlock getSkipblock(SkipblockId id) throws CothorityCommunicationException {
        SkipchainProto.GetSingleBlock request =
                SkipchainProto.GetSingleBlock.newBuilder().setId(ByteString.copyFrom(id.getId())).build();

        ByteString msg = roster.sendMessage("Skipchain/GetSingleBlock",
                request);

        try {
            SkipBlockProto.SkipBlock sb = SkipBlockProto.SkipBlock.parseFrom(msg);
            //TODO: add verification that the skipblock is valid by hashing and comparing to the id

            logger.debug("Got the following skipblock: {}", sb);
            logger.info("Successfully read skipblock");

            return sb;
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * Returns the transaction of a given skipblock, given the id of the block.
     *
     * @param id the id of the skipblock
     * @return the proto-representation of the skipblock.
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public OCSProto.Transaction getTransaction(SkipblockId id) throws CothorityCommunicationException {
        try {
            SkipBlockProto.SkipBlock sb = getSkipblock(id);

            logger.debug("Got the following skipblock: {}", sb);
            logger.info("Successfully read skipblock");

            return OCSProto.Transaction.parseFrom(sb.getData());
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * Requests the skipblock representing the write-request 'id' and returns
     * the corresponding OCSWrite-structure.
     *
     * @param id the id of the write-request
     * @return [OCSProto.Write] the write-request that can be used for
     * decryption
     * @throws CothorityCommunicationException in case of communication difficulties
     */

    public OCSProto.Write getWrite(WriteRequestId id) throws CothorityCommunicationException {
        OCSProto.Transaction transaction = getTransaction(id);
        logger.debug("Getting write-request from skipblock {}", id);
        if (!transaction.hasWrite()) {
            throw new CothorityCommunicationException("This is not an getId from a write-request");
        }
        return transaction.getWrite();
    }

    /**
     * Requests the skipblock representing the read-request 'id' and returns
     * the corresponding OCSRead-structure.
     *
     * @param id the id of the read-request
     * @return [OCSProto.Read] the read-request
     * @throws CothorityCommunicationException in case of communication difficulties
     */

    public OCSProto.Read getRead(ReadRequestId id) throws CothorityCommunicationException {
        OCSProto.Transaction transaction = getTransaction(id);
        logger.debug("Getting read-request from skipblock {}", id);
        if (!transaction.hasRead()) {
            throw new CothorityCommunicationException("This is not an getId from a read-request");
        }
        return transaction.getRead();
    }

    /**
     * Requests the re-encryption symmetricKey from the skipchain.
     * <p>
     * TODO: depending on how we decide to implement the access-rights, this
     * might go away.
     *
     * @param id the read-id
     * @return a DecryptKey that can be applied to the document to decrypt it.
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public DecryptKey getDecryptionKey(ReadRequestId id) throws CothorityCommunicationException {
        OCSProto.DecryptKeyRequest.Builder request =
                OCSProto.DecryptKeyRequest.newBuilder();
        request.setRead(ByteString.copyFrom(id.getId()));
        ByteString msg = roster.sendMessage("OnChainSecrets/DecryptKeyRequest",
                request.build());

        try {
            OCSProto.DecryptKeyReply reply = OCSProto.DecryptKeyReply.parseFrom(msg);

            logger.info("got decryption symmetricKey");
            return new DecryptKey(reply, X);
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * Requests the re-encryption symmetricKey from the skipchain, but uses an ephemeral key
     * for it.
     *
     * @param id        the read-id
     * @param signature on the read-darc from the write-request
     * @param ephemeral the ephemeral public key to use
     * @return an array of bytes with the decrypted keymaterial
     * @throws CothorityCommunicationException in case of communication difficulties
     */
    public DecryptKey getDecryptionKeyEphemeral(ReadRequestId id, DarcSignature signature, Point ephemeral) throws CothorityCommunicationException, CothorityCryptoException {
        OCSProto.DecryptKeyRequest.Builder request =
                OCSProto.DecryptKeyRequest.newBuilder();
        request.setRead(ByteString.copyFrom(id.getId()));
        request.setEphemeral(ephemeral.toProto());
        request.setSignature(signature.toProto());
        ByteString msg = roster.sendMessage("OnChainSecrets/DecryptKeyRequest",
                request.build());

        try {
            OCSProto.DecryptKeyReply reply = OCSProto.DecryptKeyReply.parseFrom(msg);

            logger.info("got decryption symmetricKey");
            return new DecryptKey(reply, X);
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    /**
     * Requests the whole chain of darcs up to the current latest darc.
     *
     * @param id one of the darcs IDs.
     * @return the darc-list, starting with the darc represented by the id given
     * @throws CothorityCommunicationException
     * @throws CothorityCryptoException
     */
    public List<Darc> getLatestDarc(DarcId id) throws CothorityCommunicationException, CothorityCryptoException {
        OCSProto.GetLatestDarc.Builder request = OCSProto.GetLatestDarc.newBuilder();
        request.setOcs(ByteString.copyFrom(ocsID.getId()));
        request.setDarcid(ByteString.copyFrom(id.getId()));
        ByteString msg = roster.sendMessage("OnChainSecrets/GetLatestDarc", request.build());

        try {
            OCSProto.GetLatestDarcReply reply = OCSProto.GetLatestDarcReply.parseFrom(msg);
            logger.info("got latestdarc");
            List<Darc> ret = new ArrayList<>();
            for (DarcOCSProto.Darc d : reply.getDarcsList()) {
                ret.add(new Darc(d));
            }
            return ret;
        } catch (InvalidProtocolBufferException e) {
            throw new CothorityCommunicationException(e);
        }
    }

    public SkipblockId getGenesis() {
        return ocsID;
    }

    public Darc getAdminDarc() throws CothorityCryptoException, CothorityCommunicationException {
        List<Darc> admins = getLatestDarc(adminDarc.getId());
        return admins.get(admins.size() - 1);
    }

    public SkipblockId getID() {
        return ocsID;
    }

    public Point getX() {
        return X;
    }

    public Roster getRoster() {
        return roster;
    }

}
