package javastuff;

import jade.lang.acl.ACLMessage;
import javastuff.onto.BookInfo;
import javastuff.onto.Goal;
import javastuff.onto.Offer;

import java.util.*;

public class BookTraderLogic {
    public class MBookInfo {

        private String bookName;
        private int bookID;

        MBookInfo(String bookName, int bookID) {
            this.bookName = bookName;
            this.bookID = bookID;
        }

        MBookInfo(BookInfo book) {
            this.bookName = book.getBookName();
            this.bookID = book.getBookID();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((bookName == null) ? 0 : bookName.hashCode());
            result = prime * result;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            MBookInfo other = (MBookInfo) obj;
            if (bookName == null) {
                if (other.bookName != null)
                    return false;
            } else if (!bookName.equals(other.bookName))
                return false;
            return true;
        }
    }

    public final long USE_AVERAGES_AFTER = 10000;//in ms
    public final long STOP_TRADING_NONGOAL_BOOKS = 150000;//in ms
    public final long TRADING_DURATION = 180000;//in ms
    public final double INCREASE_PROPOSAL_SIZE_PROB = 0.4;
    public final double MARGIN = 0.1;
    public final double SMOOTHING_FACTOR = 0.5;

    long time = 0;
    BookTrader agent;

    double minBookPrice;
    double maxBookPrice;
    double money;


    HashMap<MBookInfo, Double> priceEma = new HashMap<>();
    HashMap<MBookInfo, Double> goals = new HashMap<>();
    ArrayList<MBookInfo> books = new ArrayList<>();
    HashSet<MBookInfo> nonGoalBooks = new HashSet<>();

    private class PriceTimestamp {
        double price;
        long time;

        public PriceTimestamp(long time, double price) {
            this.price = price;
            this.time = time;
        }
    }

    public class BookPriceTuple {
        public BookInfo book;
        public double price;

        public BookPriceTuple(BookInfo book, double price) {
            this.book = book;
            this.price = price;
        }
    }

    public class OfferInfo {
        public Offer offer;
        public double value;
        public ACLMessage response;

        public OfferInfo(Offer offer, double value, ACLMessage response) {
            this.offer = offer;
            this.value = value;
            this.response = response;
        }
    }

    public BookTraderLogic(BookTrader agent) {
        this.agent = agent;
    }

    public void startTrading() {
        time = System.currentTimeMillis();

        goals.clear();
        books.clear();
        money = agent.myMoney;

        for (Goal g : agent.myGoal) {
            goals.put(new MBookInfo(g.getBook()), g.getValue());
            priceEma.put(new MBookInfo(g.getBook()), g.getValue());
        }

        for (BookInfo b : agent.myBooks)
            books.add(new MBookInfo(b));

        minBookPrice = goals.values().stream().min(Double::compareTo).get();
        maxBookPrice = goals.values().stream().max(Double::compareTo).get();
    }

    enum OfferType {
        PURCHASE,
        SALE
    }

    /**
     * Computes value of an offer
     * Do not forget to add price in his offer!!!
     */
    private double computeOfferValue(Offer o, OfferType type) {
        double p = 0;
        for (BookInfo b :
                o.getBooks()) {
            p += estimateBookUtility(new MBookInfo(b), type == OfferType.SALE ? Mode.OPTIMISTIC : Mode.PESSIMISTIC);
        }
        if (type == OfferType.PURCHASE)
            return p * (1 - MARGIN);
        else
            return p * (1 + MARGIN);
    }

    /**
     * Proposes books
     *
     * @return
     */

    private int proposedIndex = 0;

    public ArrayList<BookInfo> proposePurchase() {
        ArrayList<BookInfo> proposal = new ArrayList<>();

        int proposalSize = 1;

        for (; proposalSize + 1 < priceEma.size(); ++proposalSize)
            if (Math.random() > INCREASE_PROPOSAL_SIZE_PROB)
                break;

        int i = 0;
        for (MBookInfo b : priceEma.keySet()) {
            if (proposedIndex + 1 >= priceEma.size())
                proposedIndex = 0;

            if (i++ < proposedIndex)
                continue;
            else
                proposedIndex = i;

            if (proposalSize == 0)
                break;

            if (System.currentTimeMillis() - time > STOP_TRADING_NONGOAL_BOOKS && !goals.containsKey(b))
                continue;

            BookInfo info = new BookInfo();
            info.setBookID(b.bookID);
            info.setBookName(b.bookName);
            proposal.add(info);
            --proposalSize;
        }

        return proposal;
    }

    /**
     * Computes utility of trade
     * Utility > MARGIN is best
     * Positive value is OK
     * Value can be used for sorting (e.g. in case we get more offers from sale trader)
     *
     * @param heWants
     * @param weWant
     * @return utility; positive good, negative bad
     */
    public double acceptTrade(Offer heWants, Offer weWant) {
        double hisVal = heWants.getMoney();
        //register proposal, compute averages
        if (heWants.getBooks() != null) {
            for (BookInfo p :
                    heWants.getBooks()) {
                double price = estimateBookUtility(new MBookInfo(p), Mode.OPTIMISTIC);
                hisVal += price;
                if (priceEma.containsKey(p))
                    price = SMOOTHING_FACTOR * price + (1 - SMOOTHING_FACTOR) * priceEma.get(p);
                priceEma.put(new MBookInfo(p), price);
            }
        }

        double ourVal = weWant.getMoney();
        if (weWant.getBooks() != null) {
            for (BookInfo p :
                    weWant.getBooks()) {
                double price = estimateBookUtility(new MBookInfo(p), Mode.PESSIMISTIC);
                ourVal += price;
                if (priceEma.containsKey(p))
                    price = SMOOTHING_FACTOR * price + (1 - SMOOTHING_FACTOR) * priceEma.get(p);
                priceEma.put(new MBookInfo(p), price);
            }
        }

        return ourVal / hisVal;
    }

    /**
     * Proposes books and prices for sale
     *
     * @return
     */
    public ArrayList<Offer> proposeSale(ArrayList<BookInfo> wanted) {
        ArrayList<Offer> offers = new ArrayList<>();

        //vsetko za peniaze
        Offer o = new Offer();
        o.setBooks(wanted);
        o.setMoney(computeOfferValue(o, OfferType.SALE));
        o.setBooks(new ArrayList<>());
        offers.add(o);

        //vsetko za knihy + peniaze
        o = new Offer();
        o.setBooks(wanted);
        double p = computeOfferValue(o, OfferType.SALE);
        ArrayList<BookInfo> bs = new ArrayList<>();
        for (MBookInfo b : priceEma.keySet()) {
            if (p < 0)
                break;
            if (Math.random() < 0.3 || wanted.stream().anyMatch(x -> x.getBookName().equals(b.bookName)))
                continue;
            BookInfo book = new BookInfo();
            book.setBookName(b.bookName);
            book.setBookID(b.bookID);
            bs.add(book);
            p -= estimateBookUtility(b, Mode.PESSIMISTIC);
        }
        o.setBooks(bs);
        if (p > 0)
            o.setMoney(p);
        offers.add(o);

        return offers;
    }

    void updateLogic() {
        books.clear();
        for (BookInfo b : agent.myBooks)
            books.add(new MBookInfo(b));
        money = agent.myMoney;

        nonGoalBooks.clear();
        HashSet<MBookInfo> goals = new HashSet<>(this.goals.keySet());
        for (MBookInfo b : books) {
            if (goals.contains(b))
                goals.remove(b);
            else
                nonGoalBooks.add(b);
        }
    }


    enum Mode {
        OPTIMISTIC,
        PESSIMISTIC
    }

    /**
     * Estimates utility for the book
     *
     * @param id
     * @param mode
     * @return utility
     */
    private double estimateBookUtility(MBookInfo id, Mode mode) {
        if (goals.containsKey(id) && !nonGoalBooks.contains(id))
            return goals.get(id);

        double value = 0;

        if ((System.currentTimeMillis() - time) > USE_AVERAGES_AFTER) {
            if (priceEma.containsKey(id))
                value = priceEma.get(id);
            else
                value = mode == Mode.PESSIMISTIC ? minBookPrice : maxBookPrice;
        } else
            value = mode == Mode.PESSIMISTIC ? minBookPrice : maxBookPrice;

        //nongoal book discount befor trading end
        if ((System.currentTimeMillis() - time) > STOP_TRADING_NONGOAL_BOOKS && !goals.containsKey(id))
            return Math.max(
                    (TRADING_DURATION - (System.currentTimeMillis() - time)) * value / (TRADING_DURATION - STOP_TRADING_NONGOAL_BOOKS),
                    minBookPrice * 0.1);

        return value;
    }

}
