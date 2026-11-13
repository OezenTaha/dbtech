package de.htwberlin.dbtech.aufgaben.ue02;


/*
  @author Ingo Classen
 */

import de.htwberlin.dbtech.exceptions.DataException;
import de.htwberlin.dbtech.exceptions.DatumInVergangenheitException;
import de.htwberlin.dbtech.exceptions.KundeExistiertNichtException;
import de.htwberlin.dbtech.exceptions.ProduktExistiertNichtException;
import de.htwberlin.dbtech.exceptions.VertragExistiertBereitsException;
import de.htwberlin.dbtech.exceptions.VertragExistiertNichtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * VersicherungJdbc
 * <p>
 * Hinweis: Die Connection wird von aussen (Test) gesetzt und auch dort wieder
 * geschlossen. Diese Klasse schliesst daher nur ihre eigenen Ressourcen
 * (PreparedStatement, ResultSet) per try-with-resources.
 */
public class VersicherungJdbc implements IVersicherungJdbc {
    private static final Logger L = LoggerFactory.getLogger(VersicherungJdbc.class);
    private Connection connection;

    @Override
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    private Connection useConnection() {
        if (connection == null) {
            throw new DataException("Connection not set");
        }
        return connection;
    }

    @Override
    public List<String> kurzBezProdukte() {
        L.info("start");
        // Sortierung nach ID, da der Test die Reihenfolge KFZV, LBV, HRV erwartet
        String sql = "select KurzBez from Produkt order by ID";
        List<String> kurzBezes = new ArrayList<>();
        try (PreparedStatement ps = useConnection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                kurzBezes.add(rs.getString("KurzBez"));
            }
        } catch (SQLException e) {
            L.error("", e);
            throw new DataException(e);
        }
        L.info("ende");
        return kurzBezes;
    }

    @Override
    public Kunde findKundeById(Integer id) {
        L.info("id: " + id);
        String sql = "select ID, Name, Geburtsdatum from Kunde where ID = ?";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new KundeExistiertNichtException(id);
                }
                Date geburtsdatum = rs.getDate("Geburtsdatum");
                Kunde kunde = new Kunde(
                        rs.getInt("ID"),
                        rs.getString("Name"),
                        geburtsdatum == null ? null : geburtsdatum.toLocalDate());
                L.info("ende");
                return kunde;
            }
        } catch (SQLException e) {
            L.error("", e);
            throw new DataException(e);
        }
    }

    @Override
    public void createVertrag(Integer id, Integer produktId, Integer kundenId, LocalDate versicherungsbeginn) {
        L.info("id: " + id);
        L.info("produktId: " + produktId);
        L.info("kundenId: " + kundenId);
        L.info("versicherungsbeginn: " + versicherungsbeginn);

        // Reihenfolge der Pruefungen ist durch die Tests vorgegeben:
        // cccCreateVertrag1 nutzt eine bereits existierende Vertrags-ID mit Datum in der Vergangenheit
        // und erwartet trotzdem DatumInVergangenheitException.
        if (versicherungsbeginn.isBefore(LocalDate.now())) {
            throw new DatumInVergangenheitException(versicherungsbeginn);
        }
        if (existiert("select 1 from Vertrag where ID = ?", id)) {
            throw new VertragExistiertBereitsException(id);
        }
        if (!existiert("select 1 from Produkt where ID = ?", produktId)) {
            throw new ProduktExistiertNichtException(produktId);
        }
        if (!existiert("select 1 from Kunde where ID = ?", kundenId)) {
            throw new KundeExistiertNichtException(kundenId);
        }

        LocalDate versicherungsende = versicherungsbeginn.plusYears(1).minusDays(1);
        String sql = "insert into Vertrag (ID, Produkt_FK, Kunde_FK, Versicherungsbeginn, Versicherungsende) "
                + "values (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setInt(2, produktId);
            ps.setInt(3, kundenId);
            ps.setDate(4, Date.valueOf(versicherungsbeginn));
            ps.setDate(5, Date.valueOf(versicherungsende));
            ps.executeUpdate();
        } catch (SQLException e) {
            L.error("", e);
            throw new DataException(e);
        }
        L.info("ende");
    }

    @Override
    public BigDecimal calcMonatsrate(Integer vertragsId) {
        L.info("vertragsId: " + vertragsId);

        if (!existiert("select 1 from Vertrag where ID = ?", vertragsId)) {
            throw new VertragExistiertNichtException(vertragsId);
        }

        // Je Deckung des Vertrags: passenden Deckungsbetrag (gleiche Deckungsart, gleicher Betrag)
        // suchen und davon den Preis nehmen, der zum Versicherungsbeginn gueltig ist.
        String sql = "select coalesce(sum(dp.Preis), 0) as Monatsrate "
                + "from Vertrag v "
                + "join Deckung d on d.Vertrag_FK = v.ID "
                + "join Deckungsbetrag db on db.Deckungsart_FK = d.Deckungsart_FK "
                + "                      and db.Deckungsbetrag = d.Deckungsbetrag "
                + "join Deckungspreis dp on dp.Deckungsbetrag_FK = db.ID "
                + "where v.ID = ? "
                + "and v.Versicherungsbeginn between dp.Gueltig_Von and dp.Gueltig_Bis";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, vertragsId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); // Aggregat ohne GROUP BY liefert immer genau eine Zeile
                BigDecimal monatsrate = rs.getBigDecimal("Monatsrate");
                L.info("ende");
                return monatsrate;
            }
        } catch (SQLException e) {
            L.error("", e);
            throw new DataException(e);
        }
    }

    /**
     * Prueft, ob die Abfrage (mit genau einem Integer-Parameter) mindestens eine
     * Zeile liefert.
     */
    private boolean existiert(String sql, Integer id) {
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            L.error("", e);
            throw new DataException(e);
        }
    }

}