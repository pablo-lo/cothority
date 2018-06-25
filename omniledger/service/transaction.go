package service

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"sort"

	"github.com/dedis/cothority"
	"github.com/dedis/onet/log"
	"github.com/dedis/onet/network"

	"github.com/dedis/cothority/omniledger/collection"
	"github.com/dedis/cothority/omniledger/darc"
	"github.com/dedis/protobuf"
)

func init() {
	network.RegisterMessages(Instruction{}, ClientTransaction{},
		StateChange{})
}

// PROTOSTART
//
// option java_package = "ch.epfl.dedis.proto";
// option java_outer_classname = "TransactionProto";

// ***
// These are the messages used in the API-calls
// ***

// Instruction holds only one of Spawn, Invoke, or Delete
type Instruction struct {
	// InstanceID holds the id of the existing object that can spawn new objects.
	// It is composed of the Darc-ID + a random value generated by omniledger.
	InstanceID InstanceID
	// Nonce is monotonically increasing with regard to the darc in the objectID
	// and used to prevent replay attacks.
	// The client has to track which is the current nonce of a darc-ID.
	Nonce Nonce
	// Index and length prevent a leader from censoring specific instructions from
	// a client and still keep the other instructions valid.
	// Index is relative to the beginning of the clientTransaction.
	Index int
	// Length is the total number of instructions in this clientTransaction
	Length int
	// Spawn creates a new object
	Spawn *Spawn
	// Invoke calls a method of an existing object
	Invoke *Invoke
	// Delete removes the given object
	Delete *Delete
	// Signatures that can be verified using the darc defined by the objectID.
	Signatures []darc.Signature
}

// Nonce is used to prevent replay attacks in instructions.
type Nonce [32]byte

// An InstanceID is a unique identifier for one instance of a contract.
type InstanceID struct {
	// DarcID is the base ID of the Darc controlling access to this instance.
	DarcID darc.ID
	// SubID is a unique ID among all the objects spawned by this Darc.
	SubID SubID
}

// SubID is a 32-byte id.
type SubID [32]byte
// NewObjectID returns a new ObjectID given a slice of bytes.
func NewObjectID(buf []byte) ObjectID {
	if len(buf) != 64 {
		return ObjectID{ZeroDarc, ZeroNonce}
	}
	return ObjectID{buf[0:32], NewNonce(buf[32:64])}
}

// Slice returns concatenated DarcID and InstanceID.
func (oid InstanceID) Slice() []byte {
	var out []byte
	out = append(out, oid.DarcID[:]...)
	return append(out, oid.SubID[:]...)
}

// Equal returns if both objectIDs point to the same instance.
func (oid ObjectID) Equal(other ObjectID) bool {
	return bytes.Compare(oid.DarcID, other.DarcID) == 0 &&
		bytes.Compare(oid.InstanceID[:], other.InstanceID[:]) == 0
}

// Nonce is used to prevent replay attacks in instructions.
type Nonce [32]byte

// NewNonce returns a nonce given a slice of bytes.
func NewNonce(buf []byte) Nonce {
	if len(buf) != 32 {
		return Nonce{}
	}
	n := Nonce{}
	copy(n[:], buf)
	return n
}

// Spawn is called upon an existing object that will spawn a new object.
type Spawn struct {
	// ContractID represents the kind of contract that needs to be spawn.
	ContractID string
	// args holds all data necessary to spawn the new object.
	Args Arguments
}

// Invoke calls a method of an existing object which will update its internal
// state.
type Invoke struct {
	// Command is object specific and interpreted by the object.
	Command string
	// args holds all data necessary for the successful execution of the command.
	Args Arguments
}

// Delete removes the object.
type Delete struct {
}

// Argument is a name/value pair that will be passed to the object.
type Argument struct {
	// Name can be any name recognized by the object.
	Name string
	// Value must be binary marshalled
	Value []byte
}

// Arguments is a searchable list of arguments.
type Arguments []Argument

// NewArguments is a convenience method to create Arguments.
func NewArguments(args ...Argument) Arguments {
	return Arguments(args)
}

// Search returns the value of a given argument. If it is not found, nil
// is returned.
// TODO: An argument with nil value cannot be distinguished from
// a missing argument!
func (args Arguments) Search(name string) []byte {
	for _, arg := range args {
		if arg.Name == name {
			return arg.Value
		}
	}
	return nil
}

// Hash computes the digest of the hash function
func (instr Instruction) Hash() []byte {
	h := sha256.New()
	h.Write(instr.InstanceID.DarcID)
	h.Write(instr.InstanceID.SubID[:])
	h.Write(instr.Nonce[:])
	b := make([]byte, 4)
	binary.LittleEndian.PutUint32(b, uint32(instr.Index))
	h.Write(b)
	binary.LittleEndian.PutUint32(b, uint32(instr.Length))
	h.Write(b)
	var args []Argument
	switch {
	case instr.Spawn != nil:
		h.Write([]byte{0})
		h.Write([]byte(instr.Spawn.ContractID))
		args = instr.Spawn.Args
	case instr.Invoke != nil:
		h.Write([]byte{1})
		args = instr.Invoke.Args
	case instr.Delete != nil:
		h.Write([]byte{2})
	}
	for _, a := range args {
		h.Write([]byte(a.Name))
		h.Write(a.Value)
	}
	return h.Sum(nil)
}

// DeriveID derives a new InstanceID from the instruction's
// InstanceID, the given string, and the hash of the Instruction.
func (instr Instruction) DeriveID(what string) InstanceID {
	h := sha256.New()
	h.Write([]byte(what))
	h.Write(instr.Hash())
	for _, s := range instr.Signatures {
		// h.Write(s.Signer)
		h.Write(s.Signature)
	}
	sum := h.Sum(nil)

	var sub SubID
	copy(sub[:], sum)

	return InstanceID{
		DarcID: instr.InstanceID.DarcID,
		SubID:  sub,
	}
}

// GetContractState searches for the contract kind of this instruction and the
// attached state to it. It needs the collection to do so.
func (instr Instruction) GetContractState(coll CollectionView) (contractID string, state []byte, err error) {
	// Getting the kind is different for instructions that create a key
	// and for instructions that send a call to an existing key.
	if instr.Spawn != nil {
		// Spawning instructions have the contractID directly in the instruction.
		return instr.Spawn.ContractID, nil, nil
	}

	// For existing keys, we need to go look the kind up in our database
	// to find the kind.
	kv := coll.Get(instr.InstanceID.Slice())
	var record collection.Record
	record, err = kv.Record()
	if err != nil {
		return
	}
	var cv []interface{}
	cv, err = record.Values()
	if err != nil {
		return
	}
	var ok bool
	var contractIDBuf []byte
	contractIDBuf, ok = cv[1].([]byte)
	if !ok {
		err = errors.New("failed to cast value to bytes")
		return
	}
	contractID = string(contractIDBuf)
	state, ok = cv[0].([]byte)
	if !ok {
		err = errors.New("failed to cast value to bytes")
		return
	}
	return
}

// Action returns the action that the user wants to do with this
// instruction.
func (instr Instruction) Action() string {
	a := "invalid"
	switch {
	case instr.Spawn != nil:
		a = "spawn:" + instr.Spawn.ContractID
	case instr.Invoke != nil:
		a = "invoke:" + instr.Invoke.Command
	case instr.Delete != nil:
		a = "Delete"
	}
	return a
}

// String returns a human readable form of the instruction.
func (instr Instruction) String() string {
	var out string
	out += fmt.Sprintf("instr: %x\n", instr.Hash())
	out += fmt.Sprintf("\tdarc ID: %x\n", instr.InstanceID.DarcID)
	out += fmt.Sprintf("\tnonce: %x\n", instr.Nonce)
	out += fmt.Sprintf("\tindex: %d\n\tlength: %d\n", instr.Index, instr.Length)
	out += fmt.Sprintf("\taction: %s\n", instr.Action())
	out += fmt.Sprintf("\tsignatures: %d\n", len(instr.Signatures))
	return out
}

// SignBy gets signers to sign the (receiver) transaction.
func (instr *Instruction) SignBy(signers ...darc.Signer) error {
	// Create the request and populate it with the right identities.  We
	// need to do this prior to signing because identities are a part of
	// the digest.
	sigs := make([]darc.Signature, len(signers))
	for i, signer := range signers {
		sigs[i].Signer = signer.Identity()
	}
	instr.Signatures = sigs

	req, err := instr.ToDarcRequest()
	if err != nil {
		return err
	}
	req.Identities = make([]darc.Identity, len(signers))
	for i := range signers {
		req.Identities[i] = signers[i].Identity()
	}

	// Sign the instruction and write the signatures to it.
	digest := req.Hash()
	instr.Signatures = make([]darc.Signature, len(signers))
	for i := range signers {
		sig, err := signers[i].Sign(digest)
		if err != nil {
			return err
		}
		instr.Signatures[i] = darc.Signature{
			Signature: sig,
			Signer:    signers[i].Identity(),
		}
	}
	return nil
}

// ToDarcRequest converts the Instruction content into a darc.Request.
func (instr Instruction) ToDarcRequest() (*darc.Request, error) {
	baseID := instr.InstanceID.DarcID
	action := instr.Action()
	ids := make([]darc.Identity, len(instr.Signatures))
	sigs := make([][]byte, len(instr.Signatures))
	for i, sig := range instr.Signatures {
		ids[i] = sig.Signer
		sigs[i] = sig.Signature // TODO shallow copy is ok?
	}
	var req darc.Request
	if action == "_evolve" {
		// We make a special case for darcs evolution because the Msg
		// part of the request must be the darc ID for verification to
		// pass.
		darcBuf := instr.Invoke.Args.Search("darc")
		d, err := darc.NewFromProtobuf(darcBuf)
		if err != nil {
			return nil, err
		}
		req = darc.InitRequest(baseID, darc.Action(action), d.GetID(), ids, sigs)
	} else {
		req = darc.InitRequest(baseID, darc.Action(action), instr.Hash(), ids, sigs)
	}
	return &req, nil
}

// Instructions is a slice of Instruction
type Instructions []Instruction

// Hash returns the sha256 hash of the hash of every instruction.
func (instrs Instructions) Hash() []byte {
	h := sha256.New()
	for _, instr := range instrs {
		h.Write(instr.Hash())
	}
	return h.Sum(nil)
}

// ClientTransaction is a slice of Instructions that will be applied in order.
// If any of the instructions fails, none of them will be applied.
type ClientTransaction struct {
	Instructions Instructions
}

// ClientTransactions is a slice of ClientTransaction
type ClientTransactions []ClientTransaction

// Hash returns the sha256 hash of all client transactions.
func (cts ClientTransactions) Hash() []byte {
	h := sha256.New()
	for _, ct := range cts {
		h.Write(ct.Instructions.Hash())
	}
	return h.Sum(nil)
}

// StateChange is one new state that will be applied to the collection.
type StateChange struct {
	// StateAction can be any of Create, Update, Remove
	StateAction StateAction
	// InstanceID of the state to change
	InstanceID []byte
	// ContractID points to the contract that can interpret the value
	ContractID []byte
	// Value is the data needed by the contract
	Value []byte
}

// NewStateChange is a convenience function that fills out a StateChange
// structure.
func NewStateChange(sa StateAction, objectID InstanceID, contractID string, value []byte) StateChange {
	return StateChange{
		StateAction: sa,
		InstanceID:  objectID.Slice(),
		ContractID:  []byte(contractID),
		Value:       value,
	}
}

// String can be used in print.
func (sc StateChange) String() string {
	var out string
	out += "\nstatechange\n"
	out += fmt.Sprintf("\taction: %s\n", sc.StateAction)
	out += fmt.Sprintf("\tcontractID: %s\n", string(sc.ContractID))
	out += fmt.Sprintf("\tkey: %x\n", sc.InstanceID)
	out += fmt.Sprintf("\tvalue: %x", sc.Value)
	return out
}

// StateChanges hold a slice of StateChange
type StateChanges []StateChange

// Hash returns the sha256 of all stateChanges
func (scs StateChanges) Hash() []byte {
	h := sha256.New()
	for _, sc := range scs {
		scBuf, err := protobuf.Encode(&sc)
		if err != nil {
			log.Lvl2("Couldn't marshal transaction")
		}
		h.Write(scBuf)
	}
	return h.Sum(nil)
}

// StateAction describes how the collectionDB will be modified.
type StateAction int

const (
	// Create allows to insert a new key-value association.
	Create StateAction = iota + 1
	// Update allows to change the value of an existing key.
	Update
	// Remove allows to delete an existing key-value association.
	Remove
)

// String returns a readable output of the action.
func (sc StateAction) String() string {
	switch sc {
	case Create:
		return "Create"
	case Update:
		return "Update"
	case Remove:
		return "Remove"
	default:
		return "Invalid stateChange"
	}
}

// InstrType is the instruction type, which can be spawn, invoke or delete.
type InstrType int

const (
	// InvalidInstrType represents an error in the instruction type.
	InvalidInstrType InstrType = iota
	// SpawnType represents the spawn instruction type.
	SpawnType
	// InvokeType represents the invoke instruction type.
	InvokeType
	// DeleteType represents the delete instruction type.
	DeleteType
)

// GetType returns the type of the instruction.
func (instr Instruction) GetType() InstrType {
	if instr.Spawn != nil && instr.Invoke == nil && instr.Delete == nil {
		return SpawnType
	} else if instr.Spawn == nil && instr.Invoke != nil && instr.Delete == nil {
		return InvokeType
	} else if instr.Spawn == nil && instr.Invoke == nil && instr.Delete != nil {
		return DeleteType
	} else {
		return InvalidInstrType
	}
}

// Coin is a generic structure holding any type of coin. Coins are defined
// by a genesis coin object that is unique for each type of coin.
type Coin struct {
	// Name points to the genesis object of that coin.
	Name InstanceID
	// Value is the total number of coins of that type.
	Value uint64
}

// sortWithSalt sorts transactions according to their salted hash:
// The salt is prepended to the transactions []byte representation
// and this concatenation is hashed then.
// Using a salt here makes the resulting order of the transactions
// harder to guess.
func sortWithSalt(ts [][]byte, salt []byte) {
	less := func(i, j int) bool {
		h1 := sha256.Sum256(append(salt, ts[i]...))
		h2 := sha256.Sum256(append(salt, ts[j]...))
		return bytes.Compare(h1[:], h2[:]) == -1
	}
	sort.Slice(ts, less)
}

// sortTransactions needs to marshal transactions, if it fails to do so,
// it returns an error and leaves the slice unchanged.
// The helper functions (sortWithSalt, xorTransactions) operate on []byte
// representations directly. This allows for some more compact error handling
// when (un)marshalling.
func sortTransactions(ts []ClientTransaction) error {
	bs := make([][]byte, len(ts))
	sortedTs := make([]*ClientTransaction, len(ts))
	var err error
	var ok bool
	for i := range ts {
		bs[i], err = network.Marshal(&ts[i])
		if err != nil {
			return err
		}
	}

	// An alternative to XOR-ing the transactions would have been to
	// concatenate them and hash the result. However, if we generate the salt
	// as the hash of the concatenation of the transactions, we have to
	// concatenate them in a specific order to be deterministic.
	// This means we would have to sort them, just to get the salt.
	// In order to avoid this, we XOR them.
	salt := xorTransactions(bs)
	sortWithSalt(bs, salt)
	for i := range bs {
		_, tmp, err := network.Unmarshal(bs[i], cothority.Suite)
		if err != nil {
			return err
		}
		sortedTs[i], ok = tmp.(*ClientTransaction)
		if !ok {
			return errors.New("Data of wrong type")
		}
	}
	for i := range sortedTs {
		ts[i] = *sortedTs[i]
	}
	return nil
}

// xorTransactions returns the XOR of the hash values of all the transactions.
func xorTransactions(ts [][]byte) []byte {
	result := make([]byte, sha256.Size)
	for _, t := range ts {
		hs := sha256.Sum256(t)
		for i := range result {
			result[i] = result[i] ^ hs[i]
		}
	}
	return result
}
