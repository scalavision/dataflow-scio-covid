package nl.vroste.dataflow_scio_covid

import kantan.csv.{CellDecoder, CellEncoder, HeaderDecoder, HeaderEncoder}
import Util.dateToInstant
import org.joda.time.LocalDate

object CsvCodecs {
  // Some primitive type decoder and encoders
  implicit val localDateDecoder: CellDecoder[LocalDate] =
    CellDecoder[String].map(LocalDate.parse)

  implicit val localDateEncoder: CellEncoder[LocalDate] =
    CellEncoder[String].contramap[LocalDate](dateToInstant(_).toString)

  implicit val rivmDataRowDecoder: HeaderDecoder[RivmDataRow] = {
    implicit val dataTypeDecoder: CellDecoder[DataType] =
      CellDecoder[String].emap {
        case "Totaal"           => Right(Totaal)
        case "Ziekenhuisopname" => Right(ZiekenhuisOpname)
        case "Overleden"        => Right(Overleden)
      }

    HeaderDecoder.decoder(
      "Datum",
      "Gemeentenaam",
      "Provincienaam",
      "Type",
      "Aantal",
      "AantalCumulatief"
    )(RivmDataRow.apply)
  }

  implicit val covidStatisticsEncoder: HeaderEncoder[CovidStatistics] =
    HeaderEncoder.encoder(
      "Datum",
      "Gemeentenaam",
      "Provincienaam",
      "HospitalAdmissions",
      "HospitalAdmissionsAvg",
      "Cases",
      "CasesAvg",
      "Deaths",
      "DeathsAvg"
    ) { (stats: CovidStatistics) =>
      (
        stats.gemeente.datum,
        stats.gemeente.gemeente,
        stats.gemeente.provincie,
        stats.current.hospitalAdmissions,
        stats.average.hospitalAdmissions,
        stats.current.positiveTests,
        stats.average.positiveTests,
        stats.current.deaths,
        stats.average.deaths
      )
    }

  implicit val gemeenteDataEncoder: HeaderEncoder[GemeenteData] =
    HeaderEncoder.encoder(
      "Datum",
      "Gemeentenaam",
      "Provincienaam",
      "HospitalAdmissions",
      "Cases",
      "Deaths"
    ) { (data: GemeenteData) =>
      (
        data.datum,
        data.gemeente,
        data.provincie,
        data.counts.hospitalAdmissions,
        data.counts.positiveTests,
        data.counts.deaths
      )
    }
}