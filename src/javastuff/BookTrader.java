package javastuff;

import javafx.util.Pair;
import javastuff.BookTraderLogic;
import javastuff.onto.*;
import jade.content.ContentElement;
import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.Ontology;
import jade.content.onto.OntologyException;
import jade.content.onto.UngroundedException;
import jade.content.onto.basic.Action;
import jade.content.onto.basic.Result;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.domain.FIPAService;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.*;

import java.util.*;

/**
 * Created by Martin Pilat on 16.4.14.
 *
 * A simple (testing) version of the trading agent. The agent does not trade in any reasonable way, it only ensures it
 * does not sell bosks it does not own (but it can still happed from time to time if two agents asks for the same book
 * at the same time).
 *
 */
public class BookTrader extends Agent {

    Codec codec = new SLCodec();
    Ontology onto = BookOntology.getInstance();

    ArrayList<BookInfo> myBooks;
    ArrayList<Goal> myGoal;
    double myMoney;

    //logic of the book trader
    BookTraderLogic logic = new BookTraderLogic(this);

    @Override
    protected void setup() {
        super.setup();

        //register the codec and the ontology with the content manager
        this.getContentManager().registerLanguage(codec);
        this.getContentManager().registerOntology(onto);

        //book-trader service description
        ServiceDescription sd = new ServiceDescription();
        sd.setType("book-trader");
        sd.setName("book-trader");

        //description of this agent and the services it provides
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(this.getAID());
        dfd.addServices(sd);

        //register to DF
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }

        //add behavior which waits for the StartTrading message
        addBehaviour(new StartTradingBehaviour(this, MessageTemplate.MatchPerformative(ACLMessage.REQUEST)));
    }

    @Override
    protected void takeDown() {
        super.takeDown();
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    // waits for the StartTrading message and adds the trading behavior
    class StartTradingBehaviour extends AchieveREResponder {


        public StartTradingBehaviour(Agent a, MessageTemplate mt) {
            super(a, mt);
        }

        @Override
        protected ACLMessage handleRequest(ACLMessage request) throws NotUnderstoodException, RefuseException {

            try {
                ContentElement ce = getContentManager().extractContent(request);

                if (!(ce instanceof Action)) {
                    throw new NotUnderstoodException("");
                }
                Action a = (Action)ce;


                //we got the request to start trading
                if (a.getAction() instanceof StartTrading) {

                    //find out what our goals are
                    ACLMessage getMyInfo = new ACLMessage(ACLMessage.REQUEST);
                    getMyInfo.setLanguage(codec.getName());
                    getMyInfo.setOntology(onto.getName());

                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("environment");
                    DFAgentDescription dfd = new DFAgentDescription();
                    dfd.addServices(sd);

                    DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                    getMyInfo.addReceiver(envs[0].getName());
                    getContentManager().fillContent(getMyInfo, new Action(envs[0].getName(), new GetMyInfo()));

                    ACLMessage myInfo = FIPAService.doFipaRequestClient(myAgent, getMyInfo);

                    Result res = (Result)getContentManager().extractContent(myInfo);

                    AgentInfo ai = (AgentInfo)res.getValue();

                    myBooks = ai.getBooks();
                    myGoal = ai.getGoals();
                    myMoney = ai.getMoney();

                    //start the logic
                    logic.startTrading();

                    //add a behavior which tries to buy a book every two seconds
                    addBehaviour(new TradingBehaviour(myAgent, 2000));

                    //add a behavior which sells book to other agents
                    addBehaviour(new SellBook(myAgent, MessageTemplate.MatchPerformative(ACLMessage.CFP)));

                    //reply that we are able to start trading (the message is ignored by the environment)
                    ACLMessage reply = request.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    return reply;
                }

                throw new NotUnderstoodException("");

            } catch (Codec.CodecException e) {
                e.printStackTrace();
            } catch (OntologyException e) {
                e.printStackTrace();
            } catch (FIPAException e) {
                e.printStackTrace();
            }

            return super.handleRequest(request);
        }


        //this behavior trades with books
        class TradingBehaviour extends TickerBehaviour {


            public TradingBehaviour(Agent a, long period) {
                super(a, period);
            }

            @Override
            protected void onTick() {

                try {

                    //find other seller and prepare a CFP
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("book-trader");
                    DFAgentDescription dfd = new DFAgentDescription();
                    dfd.addServices(sd);

                    DFAgentDescription[] traders = DFService.search(myAgent, dfd);

                    ACLMessage buyBook = new ACLMessage(ACLMessage.CFP);
                    buyBook.setLanguage(codec.getName());
                    buyBook.setOntology(onto.getName());
                    buyBook.setReplyByDate(new Date(System.currentTimeMillis()+5000));

                    for (DFAgentDescription dfad : traders) {
                        if (dfad.getName().equals(myAgent.getAID()))
                            continue;
                        buyBook.addReceiver(dfad.getName());
                    }

                    ArrayList<BookInfo> bis = new ArrayList<BookInfo>();

                    //create proposals
                    ArrayList<BookInfo> proposedBooks = logic.proposePurchase();
                    SellMeBooks smb = new SellMeBooks();
                    smb.setBooks(proposedBooks);

                    getContentManager().fillContent(buyBook, new Action(myAgent.getAID(), smb));
                    addBehaviour(new ObtainBook(myAgent, buyBook));
                } catch (Codec.CodecException e) {
                    e.printStackTrace();
                } catch (OntologyException e) {
                    e.printStackTrace();
                } catch (FIPAException e) {
                    e.printStackTrace();
                }

            }
        }

        //this behavior takes care of the buying of the book itself
        class ObtainBook extends ContractNetInitiator {

            public ObtainBook(Agent a, ACLMessage cfp) {
                super(a, cfp);
            }

            Chosen c;  //we need to remember what offer we have chosen
            ArrayList<BookInfo> shouldReceive; //we also remember what the seller offered to us


            //the seller informs us it processed the order, we need to send the payment
            @Override
            protected void handleInform(ACLMessage inform) {
                try {


                    //create the transaction info and send it to the environment
                    MakeTransaction mt = new MakeTransaction();

                    mt.setSenderName(myAgent.getName());
                    mt.setReceiverName(inform.getSender().getName());
                    mt.setTradeConversationID(inform.getConversationId());

                    if (c.getOffer().getBooks() == null)
                        c.getOffer().setBooks(new ArrayList<BookInfo>());

                    mt.setSendingBooks(c.getOffer().getBooks());
                    mt.setSendingMoney(c.getOffer().getMoney());

                    if (shouldReceive == null)
                        shouldReceive = new ArrayList<BookInfo>();

                    mt.setReceivingBooks(shouldReceive);
                    mt.setReceivingMoney(0.0);

                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("environment");
                    DFAgentDescription dfd = new DFAgentDescription();
                    dfd.addServices(sd);

                    DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                    ACLMessage transReq = new ACLMessage(ACLMessage.REQUEST);
                    transReq.addReceiver(envs[0].getName());
                    transReq.setLanguage(codec.getName());
                    transReq.setOntology(onto.getName());
                    transReq.setReplyByDate(new Date(System.currentTimeMillis() + 5000));

                    getContentManager().fillContent(transReq, new Action(envs[0].getName(), mt));
                    addBehaviour(new SendBook(myAgent, transReq));

                    //update logic variables on purchase
                    logic.updateLogic();

                } catch (UngroundedException e) {
                    e.printStackTrace();
                } catch (OntologyException e) {
                    e.printStackTrace();
                } catch (Codec.CodecException e) {
                    e.printStackTrace();
                } catch (FIPAException e) {
                    e.printStackTrace();
                }

            }

            //process the offers from the sellers
            //iterate through all reponses and all their offers
            //choose the best offer and accept its proposal
            @Override
            protected void handleAllResponses(Vector responses, Vector acceptances) {

                Iterator it = responses.iterator();

                BookTraderLogic.OfferInfo currentBest = null;

                try {
                while (it.hasNext()) {
                    ACLMessage response = (ACLMessage) it.next();

                    ContentElement ce = null;

                    if (response.getPerformative() == ACLMessage.REFUSE) {
                        continue;
                    }

                    ce = getContentManager().extractContent(response);

                    ChooseFrom cf = (ChooseFrom) ce;

                    Offer weWant = new Offer();
                    weWant.setBooks(cf.getWillSell());
                    weWant.setMoney(0.0);

                    ArrayList<Offer> offers = cf.getOffers();

                    //find out which offers we can fulfill (we have all requested books and enough money)
                    ArrayList<BookTraderLogic.OfferInfo> canFulfill = new ArrayList<>();
                    for (Offer o : offers) {
                        if (o.getMoney() > myMoney)
                            continue;

                        boolean foundAll = true;
                        if (o.getBooks() != null)
                            for (BookInfo bi : o.getBooks()) {
                                String bn = bi.getBookName();
                                boolean found = false;
                                for (int j = 0; j < myBooks.size(); j++) {
                                    if (myBooks.get(j).getBookName().equals(bn)) {
                                        found = true;
                                        bi.setBookID(myBooks.get(j).getBookID());
                                        break;
                                    }
                                }
                                if (!found) {
                                    foundAll = false;
                                    break;
                                }
                            }

                        if (foundAll) {
                            //compute offers value
                            double value = logic.acceptTrade(o,weWant);
                            //we can fulfill either way but we dont want to
                            if ( value > 0)
                                canFulfill.add(logic.new OfferInfo(o,value,response));
                        }
                    }

                    //if none, we REJECT the proposal
                    if (canFulfill.size() == 0) {
                        ACLMessage acc = response.createReply();
                        acc.setPerformative(ACLMessage.REJECT_PROPOSAL);
                        acceptances.add(acc);
                    }
                    //there are some possible offers, find the best
                    else {
                        //find the best offer from this proposal
                        BookTraderLogic.OfferInfo max = canFulfill.stream().max(Comparator.comparing((BookTraderLogic.OfferInfo x) -> x.value)).get();

                        //the first one
                        if (currentBest == null) {
                            currentBest = max;
                        }
                        //we found better, reject the former and save the better
                        else if (max.value > currentBest.value) {
                            ACLMessage acc = currentBest.response.createReply();
                            acc.setPerformative(ACLMessage.REJECT_PROPOSAL);
                            acceptances.add(acc);

                            currentBest = max;
                        }
                        //not better, only reject
                        else {
                            ACLMessage acc = response.createReply();
                            acc.setPerformative(ACLMessage.REJECT_PROPOSAL);
                            acceptances.add(acc);
                        }
                    }
                }
                if (currentBest != null)
                {
                    //we accept the best one
                    ACLMessage acc = currentBest.response.createReply();
                    acc.setPerformative(ACLMessage.ACCEPT_PROPOSAL);

                    //choose an offer
                    Chosen ch = new Chosen();
                    ch.setOffer(currentBest.offer);

                    c=ch;
                    shouldReceive = ((ChooseFrom)getContentManager().extractContent(currentBest.response)).getWillSell();

                    getContentManager().fillContent(acc, ch);
                    acceptances.add(acc);

                }
                } catch (Codec.CodecException e) {
                    e.printStackTrace();
                } catch (OntologyException e) {
                    e.printStackTrace();
                }
            }
        }


        //this behavior processes the selling of books
        class SellBook extends SSResponderDispatcher {

            public SellBook(Agent a, MessageTemplate tpl) {
                super(a, tpl);
            }

            @Override
            protected Behaviour createResponder(ACLMessage initiationMsg) {
                return new SellBookResponder(myAgent, initiationMsg);
            }
        }

        class SellBookResponder extends SSContractNetResponder {

            public SellBookResponder(Agent a, ACLMessage cfp) {
                super(a, cfp);
            }

            @Override
            protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException, FailureException, NotUnderstoodException {

                try {
                    Action ac = (Action)getContentManager().extractContent(cfp);

                    SellMeBooks smb = (SellMeBooks)ac.getAction();
                    ArrayList<BookInfo> books = smb.getBooks();
                    ArrayList<BookInfo> sellBooks = new ArrayList<BookInfo>();

                    if (books == null)
                        throw new RefuseException("");

                    //find out, if we have books the agent wants
                    for (int i = 0; i < books.size(); i++) {
                        boolean found = false;
                        for (int j = 0; j < myBooks.size(); j++) {
                            if (myBooks.get(j).getBookName().equals(books.get(i).getBookName())) {
                                sellBooks.add(myBooks.get(j));
                                found = true;
                                break;
                            }
                        }
                        if (!found)
                            throw new RefuseException("");
                    }

                    ArrayList<Offer> proposedSale = logic.proposeSale(sellBooks);

                    ChooseFrom cf = new ChooseFrom();

                    cf.setWillSell(sellBooks);
                    cf.setOffers(proposedSale);

                    //send the offers
                    ACLMessage reply = cfp.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setReplyByDate(new Date(System.currentTimeMillis() + 5000));
                    getContentManager().fillContent(reply, cf);

                    return reply;
                } catch (UngroundedException e) {
                    e.printStackTrace();
                } catch (Codec.CodecException e) {
                    e.printStackTrace();
                } catch (OntologyException e) {
                    e.printStackTrace();
                }

                throw new FailureException("");
            }
            //the buyer decided to accept an offer
            @Override
            protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) throws FailureException {

                try {
                    ChooseFrom cf = (ChooseFrom)getContentManager().extractContent(propose);

                    //prepare the transaction info and send it to the environment
                    MakeTransaction mt = new MakeTransaction();

                    mt.setSenderName(myAgent.getName());
                    mt.setReceiverName(cfp.getSender().getName());
                    mt.setTradeConversationID(cfp.getConversationId());

                    if (cf.getWillSell() == null) {
                        cf.setWillSell(new ArrayList<BookInfo>());
                    }

                    mt.setSendingBooks(cf.getWillSell());
                    mt.setSendingMoney(0.0);

                    Chosen c = (Chosen)getContentManager().extractContent(accept);

                    if (c.getOffer().getBooks() == null) {
                        c.getOffer().setBooks(new ArrayList<BookInfo>());
                    }

                    mt.setReceivingBooks(c.getOffer().getBooks());
                    mt.setReceivingMoney(c.getOffer().getMoney());

                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("environment");
                    DFAgentDescription dfd = new DFAgentDescription();
                    dfd.addServices(sd);

                    DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                    ACLMessage transReq = new ACLMessage(ACLMessage.REQUEST);
                    transReq.addReceiver(envs[0].getName());
                    transReq.setLanguage(codec.getName());
                    transReq.setOntology(onto.getName());
                    transReq.setReplyByDate(new Date(System.currentTimeMillis() + 5000));

                    getContentManager().fillContent(transReq, new Action(envs[0].getName(), mt));

                    addBehaviour(new SendBook(myAgent, transReq));

                    //update logic variables on sale
                    logic.updateLogic();

                    ACLMessage reply = accept.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    return reply;

                } catch (UngroundedException e) {
                    e.printStackTrace();
                } catch (OntologyException e) {
                    e.printStackTrace();
                } catch (Codec.CodecException e) {
                    e.printStackTrace();
                } catch (FIPAException e) {
                    e.printStackTrace();
                }

                throw new FailureException("");
            }
        }

        //after the transaction is complete (the environment returned an INFORM), we update our information
        class SendBook extends AchieveREInitiator {

            public SendBook(Agent a, ACLMessage msg) {
                super(a, msg);
            }

            @Override
            protected void handleInform(ACLMessage inform) {

                try {
                    ACLMessage getMyInfo = new ACLMessage(ACLMessage.REQUEST);
                    getMyInfo.setLanguage(codec.getName());
                    getMyInfo.setOntology(onto.getName());

                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("environment");
                    DFAgentDescription dfd = new DFAgentDescription();
                    dfd.addServices(sd);

                    DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                    getMyInfo.addReceiver(envs[0].getName());
                    getContentManager().fillContent(getMyInfo, new Action(envs[0].getName(), new GetMyInfo()));

                    ACLMessage myInfo = FIPAService.doFipaRequestClient(myAgent, getMyInfo);

                    Result res = (Result)getContentManager().extractContent(myInfo);

                    AgentInfo ai = (AgentInfo)res.getValue();

                    myBooks = ai.getBooks();
                    myGoal = ai.getGoals();
                    myMoney = ai.getMoney();
                } catch (OntologyException e) {
                    e.printStackTrace();
                } catch (FIPAException e) {
                    e.printStackTrace();
                } catch (Codec.CodecException e) {
                    e.printStackTrace();
                }

            }
        }
    }

}
