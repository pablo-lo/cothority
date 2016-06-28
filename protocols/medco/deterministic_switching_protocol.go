package medco

import (
	"errors"
	"github.com/dedis/cothority/log"
	"github.com/dedis/cothority/network"
	"github.com/dedis/cothority/sda"
	. "github.com/dedis/cothority/services/medco/libmedco"
	"github.com/dedis/crypto/abstract"
)

const DETERMINISTIC_SWITCHING_PROTOCOL_NAME = "DeterministicSwitching"

func init() {
	network.RegisterMessageType(DeterministicSwitchedMessage{})
	network.RegisterMessageType(CipherText{})
	network.RegisterMessageType(CipherVector{})
	sda.ProtocolRegisterName(DETERMINISTIC_SWITCHING_PROTOCOL_NAME, NewDeterministSwitchingProtocol)
}

//DeterministicSwitchedMessage represents a deterministic switching message containing the processed ciphervectors,
//their original ephemeral keys
type DeterministicSwitchedMessage struct {
	Data                  map[TempID]CipherVector
	OriginalEphemeralKeys map[TempID][]abstract.Point
}

//DeterministicSwitchedStruct contains a sda treenode and a deterministic switching message
type DeterministicSwitchedStruct struct {
	*sda.TreeNode
	DeterministicSwitchedMessage
}

//DeterministicSwitchingProtocol defines the elements of deterministicSwitching protocol
type DeterministicSwitchingProtocol struct {
	//node doing the protocol
	*sda.TreeNodeInstance

	// Protocol feedback channel
	FeedbackChannel chan map[TempID]DeterministCipherVector

	// Protocol communication channels
	PreviousNodeInPathChannel chan DeterministicSwitchedStruct

	// Protocol state data
	nextNodeInCircuit *sda.TreeNode
	TargetOfSwitch    *map[TempID]CipherVector
	SurveyPHKey       *abstract.Scalar
	originalEphemKeys map[TempID][]abstract.Point
}

//NewDeterministSwitchingProtocol constructor for DeterministicSwitchingProtocol
func NewDeterministSwitchingProtocol(n *sda.TreeNodeInstance) (sda.ProtocolInstance, error) {
	deterministicSwitchingProtocol := &DeterministicSwitchingProtocol{
		TreeNodeInstance: n,
		FeedbackChannel:  make(chan map[TempID]DeterministCipherVector),
	}

	if err := deterministicSwitchingProtocol.RegisterChannel(&deterministicSwitchingProtocol.PreviousNodeInPathChannel); err != nil {
		return nil, errors.New("couldn't register data reference channel: " + err.Error())
	}

	var i int
	var node *sda.TreeNode
	var nodeList = n.Tree().List()
	for i, node = range nodeList {
		if n.TreeNode().Equal(node) {
			deterministicSwitchingProtocol.nextNodeInCircuit = nodeList[(i+1)%len(nodeList)]
			break
		}
	}

	return deterministicSwitchingProtocol, nil
}

func (p *DeterministicSwitchingProtocol) Start() error {
	if p.TargetOfSwitch == nil {
		return errors.New("No map given as deterministic switching target.")
	}
	if p.SurveyPHKey == nil {
		return errors.New("No PH key given.")
	}

	log.Lvl1(p.Name(), "started a Deterministic Switching Protocol (", len(*p.TargetOfSwitch), "rows )")

	p.originalEphemKeys = make(map[TempID][]abstract.Point, len(*p.TargetOfSwitch))

	for k := range *p.TargetOfSwitch {
		p.originalEphemKeys[k] = make([]abstract.Point, len((*p.TargetOfSwitch)[k]))
		for i, c := range (*p.TargetOfSwitch)[k] {
			p.originalEphemKeys[k][i] = c.K
		}
	}
	//forward message to next node
	p.sendToNext(&DeterministicSwitchedMessage{*p.TargetOfSwitch,
		p.originalEphemKeys})

	return nil
}

func (p *DeterministicSwitchingProtocol) Dispatch() error {

	deterministicSwitchingTarget := <-p.PreviousNodeInPathChannel

	//each node should use one different PH contribution per survey
	phContrib := p.Suite().Point().Mul(p.Suite().Point().Base(), *p.SurveyPHKey)
	for k, v := range deterministicSwitchingTarget.Data {
		v.DeterministicSwitching(&v, p.Private(), phContrib)
		deterministicSwitchingTarget.Data[k] = v
	}

	//if root, then protocol reached the end
	if p.IsRoot() {
		deterministicSwitchedData := make(map[TempID]DeterministCipherVector, len(deterministicSwitchingTarget.Data))
		for k, v := range deterministicSwitchingTarget.Data {
			deterministicSwitchedData[k] = make(DeterministCipherVector, len(v))
			for i, c := range v {
				deterministicSwitchedData[k][i] = DeterministCipherText{c.C}
			}
		}
		log.Lvl1(p.ServerIdentity(), "completed deterministic switching (", len(deterministicSwitchedData), "row )")
		p.FeedbackChannel <- deterministicSwitchedData
	} else { //forward switched message
		log.Lvl1(p.ServerIdentity(), "carried on deterministic switching.")
		p.sendToNext(&deterministicSwitchingTarget.DeterministicSwitchedMessage)
	}

	return nil
}

// Sends the message msg to the next node in the circuit based on the next TreeNode in Tree.List() If not visited yet.
// If the message already visited the next node, doesn't send and returns false. Otherwise, return true.
func (p *DeterministicSwitchingProtocol) sendToNext(msg interface{}) {
	err := p.SendTo(p.nextNodeInCircuit, msg)
	if err != nil {
		log.Lvl1("Had an error sending a message: ", err)
	}
}
