/*
 * Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>
 * This file is part of EVSimulatorApp
 *
 * Licensed under the Affero GPL license, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

using System.Text;

namespace cloud.charging.open.protocols.ISO15118.EXI.SourceGenerator.Emit
{

    /// <summary>
    /// The AGPL licence header every generated port file carries, emitted as line comments so it
    /// can follow the <c>// &lt;auto-generated/&gt;</c> marker the driver identifies generated files
    /// by (see <c>Program.RemoveStaleOutput</c>) — the licence must not become the first line.
    /// One place, so a relicensing touches the generator rather than every emitted file.
    /// </summary>
    internal static class GeneratedFileLicense
    {

        public static void AppendTo(StringBuilder sb)
        {
            sb.AppendLine("//");
            sb.AppendLine("// Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>");
            sb.AppendLine("// This file is part of WWCP ISO/IEC 15118 <https://github.com/OpenChargingCloud/WWCP_ISO15118>");
            sb.AppendLine("//");
            sb.AppendLine("// Licensed under the Affero GPL license, Version 3.0 (the \"License\");");
            sb.AppendLine("// you may not use this file except in compliance with the License.");
            sb.AppendLine("// You may obtain a copy of the License at");
            sb.AppendLine("//");
            sb.AppendLine("//     http://www.gnu.org/licenses/agpl.html");
            sb.AppendLine("//");
            sb.AppendLine("// Unless required by applicable law or agreed to in writing, software");
            sb.AppendLine("// distributed under the License is distributed on an \"AS IS\" BASIS,");
            sb.AppendLine("// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.");
            sb.AppendLine("// See the License for the specific language governing permissions and");
            sb.AppendLine("// limitations under the License.");
        }

    }

}
