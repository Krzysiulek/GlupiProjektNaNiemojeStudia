package prs.project;

import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import prs.project.checker.Ledger;
import prs.project.controllers.Settings;
import prs.project.model.Product;
import prs.project.model.Warehouse;
import prs.project.status.ReplyToAction;
import prs.project.task.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

import static prs.project.task.SterowanieAkcja.ZAMKNIJ_SKLEP;
import static prs.project.task.WycenaAkcje.PODAJ_CENE;
import static prs.project.task.ZamowieniaAkcje.REZERWACJA;
import static prs.project.task.ZaopatrzenieAkcje.POJEDYNCZE_ZAOPATRZENIE;

@Service
@Slf4j
public class ParallelExecutor {

    @Autowired
    Ledger ledger;

    Settings settings;
    List<Akcja> akcje = new ArrayList<>();
    boolean active = true;
    Set<Enum> mojeTypy = new HashSet<>();
    ConcurrentLinkedDeque<Akcja> kolejka = new ConcurrentLinkedDeque();
    ConcurrentLinkedDeque<Akcja> kolejkaIndependent = new ConcurrentLinkedDeque();
    Warehouse magazyn = new Warehouse();
    EnumMap<Product, Long> sprzedaz = new EnumMap(Product.class);
    EnumMap<Product, Long> rezerwacje = new EnumMap(Product.class);
    Long promoLicznik = 0L;

    @SneakyThrows
    public ParallelExecutor(Settings settings, List<Akcja> akcje) {
        this.settings = settings;
        this.akcje = akcje;
        Arrays.stream(Product.values()).forEach(p -> sprzedaz.put(p, 0L));
        Arrays.stream(Product.values()).forEach(p -> rezerwacje.put(p, 0L));

        mojeTypy.addAll(Wycena.valueOf(settings.getWycena()).getAkceptowane());
        mojeTypy.addAll(Zamowienia.valueOf(settings.getZamowienia()).getAkceptowane());
        mojeTypy.addAll(Zaopatrzenie.valueOf(settings.getZaopatrzenie()).getAkceptowane());
        mojeTypy.addAll(Wydarzenia.valueOf(settings.getWydarzenia()).getAkceptowane());
        mojeTypy.addAll(Arrays.asList(SterowanieAkcja.values()));

        Thread thread = new Thread(this::processStandardThread);
        Thread thread2 = new Thread(this::processPodajCeneThread);

        thread.start();
        thread2.start();
    }

    public void process(Akcja jednaAkcja) {
        List<Enum<? extends Enum<?>>> independentActions = Arrays.asList(PODAJ_CENE);

        Stream.of(jednaAkcja)
                .filter(akcja -> mojeTypy.contains(akcja.getTyp()))
                .filter(akcja -> !independentActions.contains(akcja.getTyp()))
                .forEach(akcja -> kolejka.add(akcja));

        Stream.of(jednaAkcja)
                .filter(akcja -> mojeTypy.contains(akcja.getTyp()))
                .filter(akcja -> independentActions.contains(akcja.getTyp()))
                .forEach(akcja -> kolejkaIndependent.add(akcja));
    }

    public void threadProcess(Akcja akcja) {
        if (akcja != null) {
            ReplyToAction odpowiedz = procesujAkcje(akcja);
            try {
                wyslijOdpowiedzLokalnie(odpowiedz);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void processStandardThread() {
        while (active) {
            Akcja action = getStandardAction();
            threadProcess(action);
        }
    }

    private void processPodajCeneThread() {
        while (active) {
            Akcja action = getPodajCeneAction();
            threadProcess(action);
        }
    }

    private synchronized Akcja getStandardAction() {
        if (!kolejka.isEmpty()) {
            return kolejka.pollFirst();
        }

        return null;
    }

    private synchronized Akcja getPodajCeneAction() {
        if (!kolejkaIndependent.isEmpty()) {
            return kolejkaIndependent.pollFirst();
        }

        return null;
    }

    private ReplyToAction procesujAkcje(Akcja akcja) {
        log.info("Procesuje " + akcja.getTyp());
        ReplyToAction odpowiedz = ReplyToAction.builder()
                .typ(akcja.getTyp())
                .id(akcja.getId())
                .build();

        if (PODAJ_CENE.equals(akcja.getTyp())) {
            executePodajCene(akcja, odpowiedz);
        }
        if (WycenaAkcje.ZMIEN_CENE.equals(akcja.getTyp())) {
            executeZmienCene(akcja, odpowiedz);
        }

        if (WydarzeniaAkcje.RAPORT_SPRZEDAŻY.equals(akcja.getTyp())) {
            odpowiedz.setRaportSprzedaży(sprzedaz);
        }
        if (WydarzeniaAkcje.INWENTARYZACJA.equals(akcja.getTyp())) {
            odpowiedz.setStanMagazynów(magazyn.getStanMagazynowy());
        }
        if (WydarzeniaAkcje.WYCOFANIE.equals(akcja.getTyp())) {
            executeWycofanie(akcja, odpowiedz);
        }
        if (WydarzeniaAkcje.PRZYWROCENIE.equals(akcja.getTyp())) {
            executePrzywrocenie(akcja, odpowiedz);
        }

        if (ZamowieniaAkcje.POJEDYNCZE_ZAMOWIENIE.equals(akcja.getTyp())) {
            executePojedynczeZamowienie(akcja, odpowiedz);
        }
        if (ZamowieniaAkcje.GRUPOWE_ZAMOWIENIE.equals(akcja.getTyp())) {
            executeGrupoweZamowienie(akcja, odpowiedz);
        }
        if (REZERWACJA.equals(akcja.getTyp())) {
            executeRezerwacja(akcja, odpowiedz);
        }
        if (ZamowieniaAkcje.ODBIÓR_REZERWACJI.equals(akcja.getTyp())) {
            executeOdbiorRezerwacji(akcja, odpowiedz);
        }

        if (POJEDYNCZE_ZAOPATRZENIE.equals(akcja.getTyp())) {
            executePojedynczeZaopatrzenie(akcja, odpowiedz);
        }
        if (ZaopatrzenieAkcje.GRUPOWE_ZAOPATRZENIE.equals(akcja.getTyp())) {
            executeGrupoweZaopatrzenie(akcja, odpowiedz);
        }

        if (ZAMKNIJ_SKLEP.equals(akcja.getTyp())) {
            synchronized (this) {
                odpowiedz.setStanMagazynów(magazyn.getStanMagazynowy());
                odpowiedz.setGrupaProduktów(magazyn.getCeny());
            }
        }

        log.info("Processed {} ", akcja.getTyp());
        return odpowiedz;
    }


    private synchronized void executeGrupoweZaopatrzenie(Akcja akcja, ReplyToAction odpowiedz) {
        odpowiedz.setGrupaProduktów(akcja.getGrupaProduktów());
        odpowiedz.setZebraneZaopatrzenie(true);
        akcja.getGrupaProduktów().entrySet().stream()
                .forEach(produkt -> {
                    Long naMagazynie = magazyn.getStanMagazynowy().get(produkt.getKey());
                    if (magazyn.getStanMagazynowy().get(akcja.getProduct()) >= 0) {
                        magazyn.getStanMagazynowy().put(produkt.getKey(), naMagazynie + produkt.getValue());
                    }
                });
    }

    private synchronized void executePojedynczeZaopatrzenie(Akcja akcja, ReplyToAction odpowiedz) {
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setLiczba(akcja.getLiczba());
        Long naMagazynie = magazyn.getStanMagazynowy().get(akcja.getProduct());
        odpowiedz.setZebraneZaopatrzenie(true);
        if (magazyn.getStanMagazynowy().get(akcja.getProduct()) >= 0) {
            magazyn.getStanMagazynowy().put(akcja.getProduct(), naMagazynie + akcja.getLiczba());
        }
    }

    @SneakyThrows
    private synchronized void executeOdbiorRezerwacji(Akcja akcja, ReplyToAction odpowiedz) {
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setLiczba(akcja.getLiczba());

        Long naMagazynie = rezerwacje.get(akcja.getProduct());
        if (naMagazynie >= akcja.getLiczba()) {
            odpowiedz.setZrealizowaneZamowienie(true);
            rezerwacje.put(akcja.getProduct(), rezerwacje.get(akcja.getProduct()) - akcja.getLiczba());
        } else {
            odpowiedz.setZrealizowaneZamowienie(false);
        }
    }

    @SneakyThrows
    private synchronized void executeRezerwacja(Akcja akcja, ReplyToAction odpowiedz) {
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setLiczba(akcja.getLiczba());

        Long naMagazynie = magazyn.getStanMagazynowy().get(akcja.getProduct());
        if (naMagazynie >= akcja.getLiczba()) {
            odpowiedz.setZrealizowaneZamowienie(true);
            magazyn.getStanMagazynowy().put(akcja.getProduct(), naMagazynie - akcja.getLiczba());
            rezerwacje.put(akcja.getProduct(), rezerwacje.get(akcja.getProduct()) + akcja.getLiczba());
        } else {
            odpowiedz.setZrealizowaneZamowienie(false);
        }
    }

    private synchronized void executeGrupoweZamowienie(Akcja akcja, ReplyToAction odpowiedz) {
        odpowiedz.setGrupaProduktów(akcja.getGrupaProduktów());
        odpowiedz.setZrealizowaneZamowienie(true);
        akcja.getGrupaProduktów().entrySet().stream()
                .forEach(produkt -> {
                    Long naMagazynie = magazyn.getStanMagazynowy().get(produkt.getKey());
                    if (naMagazynie < produkt.getValue()) {
                        odpowiedz.setZrealizowaneZamowienie(false);
                    }
                });
        if (odpowiedz.getZrealizowaneZamowienie()) {
            akcja.getGrupaProduktów().entrySet().stream()
                    .forEach(produkt -> {
                        Long naMagazynie = magazyn.getStanMagazynowy().get(produkt.getKey());
                        magazyn.getStanMagazynowy().put(produkt.getKey(), naMagazynie - produkt.getValue());
                        sprzedaz.put(produkt.getKey(), sprzedaz.get(produkt.getKey()) + produkt.getValue());
                    });
        }
    }

    @SneakyThrows
    private synchronized void executePojedynczeZamowienie(Akcja akcja, ReplyToAction odpowiedz) {
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setLiczba(akcja.getLiczba());

        Long naMagazynie = magazyn.getStanMagazynowy().get(akcja.getProduct());
        if (naMagazynie >= akcja.getLiczba()) {
            odpowiedz.setZrealizowaneZamowienie(true);
            magazyn.getStanMagazynowy().put(akcja.getProduct(), naMagazynie - akcja.getLiczba());
            sprzedaz.put(akcja.getProduct(), sprzedaz.get(akcja.getProduct()) + akcja.getLiczba());
        } else {
            odpowiedz.setZrealizowaneZamowienie(false);
        }
    }

    private synchronized void executePrzywrocenie(Akcja akcja, ReplyToAction odpowiedz) {
        magazyn.getStanMagazynowy().put(akcja.getProduct(), 0L);
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setZrealizowanePrzywrócenie(true);
    }

    private synchronized void executeWycofanie(Akcja akcja, ReplyToAction odpowiedz) {
        magazyn.getStanMagazynowy().put(akcja.getProduct(), -9999999L);
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setZrealizowaneWycofanie(true);
    }

    private synchronized void executeZmienCene(Akcja akcja, ReplyToAction odpowiedz) {
        magazyn.getCeny().put(akcja.getProduct(), akcja.getCena());
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setCenaZmieniona(true);
        odpowiedz.setCena(akcja.getCena());
    }

    private synchronized void executePodajCene(Akcja akcja, ReplyToAction odpowiedz) {
        odpowiedz.setProdukt(akcja.getProduct());
        odpowiedz.setCena(magazyn.getCeny().get(akcja.getProduct()));
        if (mojeTypy.contains(Wycena.PROMO_CO_10_WYCEN)) {
            promoLicznik++;
            if (promoLicznik == 10)
                odpowiedz.setCena(0L);
        }
    }

    public void wyslijOdpowiedz(ReplyToAction odpowiedz) throws IOException {
        odpowiedz.setStudentId(settings.getNumerIndeksu());
        HttpPost post = new HttpPost("http://localhost:8080/action/log");

        JsonMapper mapper = new JsonMapper();
        String json = mapper.writeValueAsString(odpowiedz);
        StringEntity entity = new StringEntity(json);
        log.info(json);
        post.setEntity(entity);
        post.setHeader("Accept", "application/json");
        post.setHeader("Content-type", "application/json");

        try (CloseableHttpClient httpClient = HttpClients.createDefault();
             CloseableHttpResponse response = httpClient.execute(post)) {

            HttpEntity rEntity = response.getEntity();
            if (rEntity != null) {
                // return it as a String
                String result = EntityUtils.toString(rEntity);
                log.info(result);
            }
        }
    }

    public void wyslijOdpowiedzLokalnie(ReplyToAction odpowiedz) throws IOException {
        odpowiedz.setStudentId(settings.getNumerIndeksu());
        try {
            ledger.addReply(odpowiedz);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (ZAMKNIJ_SKLEP.equals(odpowiedz.getTyp())) {
            Warehouse magazyn = new Warehouse();
            EnumMap<Product, Long> sprzedaz = new EnumMap(Product.class);
            EnumMap<Product, Long> rezerwacje = new EnumMap(Product.class);
            Arrays.stream(Product.values()).forEach(p -> rezerwacje.put(p, 0L));
            Arrays.stream(Product.values()).forEach(p -> sprzedaz.put(p, 0L));
            Long promoLicznik = 0L;
        }
    }
}
